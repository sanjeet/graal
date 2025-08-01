/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.substitutions;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.graalvm.collections.EconomicMap;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.classfile.descriptors.Type;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols;
import com.oracle.truffle.espresso.impl.ClassLoadingEnv;
import com.oracle.truffle.espresso.impl.ContextAccessImpl;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.MethodKey;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;

/**
 * Substitutions/intrinsics for Espresso.
 * <p>
 * Some substitutions are globally defined, others runtime-dependent. The global ones are loaded via
 * {@link ServiceLoader}. Iterating over the collection in this class allows to register them
 * directly, and assign to each of them a node, which will dispatch them directly, without the need
 * for reflection. In practice, this allows inlining.
 * <p>
 * To register a substitution in Espresso:
 * <li>Create a class annotated with {@link EspressoSubstitutions}. Its name must be the fully
 * qualified name of the substituted class, to which is prepended "Target_" and each "." is replaced
 * by a "_". For example, java.lang.Class becomes Target_java_lang_Class. Keep the "$" in case you
 * want to substitute an inner class.
 * <li>For each substituted method of the class, create a method in the "Target_" class. This method
 * should be annotated with {@link Substitution}. If the method is an instance method, it must be
 * annotated with {@link Substitution#hasReceiver()} = true
 * <li>If the method has a primitive signature, the signature of the substitution should be the
 * same, save for a potential receiver. If there are reference types in the signature, Simply put a
 * StaticObject type instead, but annotate the argument with {@link JavaType}. This must be done for
 * EVERY reference argument, even the receiver.
 * <li>If the class of the reference argument is public, (/ex {@link Class}), you can simply put @
 * {@link JavaType}({@link Class}.class) in the annotation. If the class is private, you have to put
 * {@link JavaType}(typeName() = ...), where "..." is the internal name of the class (ie: the
 * qualified name, where all "." are replaced with "/", an "L" is prepended, and a ";" is appended.
 * /ex: java.lang.Class becomes Ljava/lang/Class;.)
 * <li>The name of the method in the substitution can be the same as the substitution target, and it
 * will work out. Note that it might happen that a class overloads a method, and since types gets
 * "erased" in the substitution, it is not possible to give the same name to both. If that happens,
 * you can use the {@link Substitution#methodName()} value. For example, in {@link java.util.Arrays}
 * , the toString(... array) method is overloaded with every primitive array type. In that case you
 * can write in the substitution
 *
 * <pre>
 * {@literal @}Substitution(methodName = "toString")
 * public static @JavaType(String.class) StaticObject toString_byte(@JavaType(byte[].class) StaticObject array) {
 *     ...
 * }
 *
 * {@literal @}Substitution(methodName = "toString")
 * public static @JavaType(String.class) StaticObject toString_int(@JavaType(int[].class) StaticObject array) {
 *     ...
 * }
 * </pre>
 *
 * and so on so forth.
 * <li>Additionally, some substitutions may not be given a meta accessor as parameter, but may need
 * to get the meta from somewhere. Regular meta obtention can be done through
 * {@link EspressoContext#get(Node)}, but this is quite a slow access. As such, it is possible to
 * append the meta as an argument to the substitution, annotated with {@link Inject} . Once again,
 * the processor will generate all that is needed to give the meta.
 * <p>
 * <p>
 * The order of arguments matter: First, the actual guest arguments, next the list of guest method
 * nodes, and finally the meta to be injected.
 */
public final class Substitutions extends ContextAccessImpl {

    private static final TruffleLogger logger = TruffleLogger.getLogger(EspressoLanguage.ID, Substitutions.class);

    public static TruffleLogger getLogger() {
        return logger;
    }

    public static void ensureInitialized() {
        /* nop */
    }

    /**
     * We use a factory to create the substitution node once the target Method instance is known.
     */
    public interface EspressoRootNodeFactory {
        /**
         * Creates a node with a substitution for the given method, or returns <code>null</code> if
         * the substitution does not apply e.g. static substitutions are only valid classes/methods
         * on the boot and platform class loaders.
         *
         * @param forceValid if true, skips all checks to validate the substitution for the given
         *            method.
         */
        EspressoRootNode createNodeIfValid(Method method, boolean forceValid);

        default EspressoRootNode createNodeIfValid(Method method) {
            return createNodeIfValid(method, false);
        }
    }

    private static final EconomicMap<MethodKey, JavaSubstitution.Factory> GLOBAL_SUBSTITUTIONS = EconomicMap.create();

    private final ConcurrentHashMap<MethodKey, EspressoRootNodeFactory> runtimeSubstitutions = new ConcurrentHashMap<>();

    static {
        for (JavaSubstitution.Factory factory : SubstitutionCollector.getInstances(JavaSubstitution.Factory.class)) {
            registerStaticSubstitution(factory);
        }
    }

    public Substitutions(EspressoContext context) {
        super(context);
    }

    private static MethodKey getMethodKey(Method method) {
        return new MethodKey(method);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerStaticSubstitution(JavaSubstitution.Factory substitutorFactory) {
        List<Symbol<Type>> parameterTypes = new ArrayList<>();
        for (int i = substitutorFactory.hasReceiver() ? 1 : 0; i < substitutorFactory.parameterTypes().length; i++) {
            String type = substitutorFactory.parameterTypes()[i];
            parameterTypes.add(EspressoSymbols.SYMBOLS.putType(type));
        }
        Symbol<Type> returnType = EspressoSymbols.SYMBOLS.putType(substitutorFactory.returnType());
        Symbol<Signature> signature = EspressoSymbols.SYMBOLS.putSignature(returnType, parameterTypes.toArray(Symbol.EMPTY_ARRAY));

        String[] classNames = substitutorFactory.substitutionClassNames();
        String[] methodNames = substitutorFactory.getMethodNames();
        for (int i = 0; i < classNames.length; i++) {
            String internalName = classNames[i];
            Symbol<Type> classType = EspressoSymbols.SYMBOLS.putType(internalName);
            Symbol<Name> methodName = EspressoSymbols.SYMBOLS.putName(methodNames[i]);
            registerStaticSubstitution(classType, methodName, signature, substitutorFactory, true);
        }
    }

    private static void registerStaticSubstitution(Symbol<Type> type, Symbol<Name> methodName, Symbol<Signature> signature, JavaSubstitution.Factory factory, boolean throwIfPresent) {
        MethodKey key = new MethodKey(type, methodName, signature, !factory.hasReceiver());
        if (throwIfPresent && GLOBAL_SUBSTITUTIONS.containsKey(key)) {
            throw EspressoError.shouldNotReachHere("substitution already registered" + key);
        }
        GLOBAL_SUBSTITUTIONS.put(key, factory);
    }

    public void registerRuntimeSubstitution(Symbol<Type> type, Symbol<Name> methodName, Symbol<Signature> signature, boolean isStatic, EspressoRootNodeFactory factory, boolean throwIfPresent) {
        MethodKey key = new MethodKey(type, methodName, signature, isStatic);

        if (GLOBAL_SUBSTITUTIONS.containsKey(key)) {
            getLogger().log(Level.FINE, "Runtime substitution shadowed by global one: " + key);
        }

        if (throwIfPresent && runtimeSubstitutions.containsKey(key)) {
            throw EspressoError.shouldNotReachHere("substitution already registered " + key);
        }
        runtimeSubstitutions.put(key, factory);
    }

    public void removeRuntimeSubstitution(Method method) {
        MethodKey key = getMethodKey(method);
        runtimeSubstitutions.remove(key);
    }

    public static JavaSubstitution.Factory lookupSubstitution(Method m) {
        return GLOBAL_SUBSTITUTIONS.get(getMethodKey(m));
    }

    /**
     * Returns a node with a substitution for the given method, or <code>null</code> if the
     * substitution does not exist or does not apply.
     */
    public EspressoRootNode get(Method method) {
        // Look into the static substitutions.
        MethodKey key = getMethodKey(method);
        JavaSubstitution.Factory staticSubstitutionFactory = GLOBAL_SUBSTITUTIONS.get(key);
        if (staticSubstitutionFactory != null && staticSubstitutionFactory.isValidFor(method.getLanguage())) {
            EspressoRootNode root = createRootNodeFromSubstitution(method, staticSubstitutionFactory);
            if (root != null) {
                return root;
            }
        }

        // Look into registered substitutions at runtime (through JNI RegisterNatives)
        EspressoRootNodeFactory factory = runtimeSubstitutions.get(key);
        if (factory != null) {
            return factory.createNodeIfValid(method);
        }

        // Failed to find a substitution.
        return null;
    }

    private static EspressoRootNode createRootNodeFromSubstitution(Method method, JavaSubstitution.Factory staticSubstitutionFactory) {
        StaticObject classLoader = method.getDeclaringKlass().getDefiningClassLoader();
        ClassLoadingEnv env = method.getContext().getClassLoadingEnv();
        if (env.loaderIsBootOrPlatform(classLoader)) {
            return EspressoRootNode.createSubstitution(method.getMethodVersion(), staticSubstitutionFactory);
        }
        getLogger().warning(new Supplier<String>() {
            @Override
            public String get() {
                StaticObject givenLoader = method.getDeclaringKlass().getDefiningClassLoader();
                return "Static substitution for " + method + " does not apply.\n" +
                                "\tExpected class loader: Boot (null) or platform class loader\n" +
                                "\tGiven class loader: " + InteropLibrary.getUncached().toDisplayString(givenLoader, false) + "\n";
            }
        });
        return null;
    }

    @TruffleBoundary
    public boolean hasSubstitutionFor(Method method) {
        MethodKey key = getMethodKey(method);
        return GLOBAL_SUBSTITUTIONS.containsKey(key) || runtimeSubstitutions.containsKey(key);
    }
}
