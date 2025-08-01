/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.svm.core.classinitialization;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.oracle.svm.core.hub.RuntimeClassLoading;
import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CFunctionPointer;

import com.oracle.svm.core.FunctionPointerHolder;
import com.oracle.svm.core.c.InvokeJavaFunctionPointer;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.PredefinedClassesSupport;
import com.oracle.svm.core.jdk.InternalVMMethod;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.core.thread.ContinuationSupport;
import com.oracle.svm.core.thread.JavaThreads;
import com.oracle.svm.core.thread.Target_jdk_internal_vm_Continuation;
import com.oracle.svm.core.util.VMError;

import jdk.internal.misc.Unsafe;
import jdk.internal.reflect.Reflection;

/**
 * Information about the runtime class initialization state of a {@link DynamicHub class}, and
 * {@link #slowPath(ClassInitializationInfo, DynamicHub) implementation} of class initialization
 * according to the Java VM specification.
 * <p>
 * The information is not directly stored in {@link DynamicHub} because 1) the class initialization
 * state is mutable while {@link DynamicHub} must be immutable, and 2) few classes require
 * initialization at runtime so factoring out the information reduces image size.
 * <p>
 * The {@link #typeReached} for all super types must have a value whose ordinal is greater or equal
 * to its own value. Concretely, {@link TypeReached#REACHED} types must have all super types
 * {@link TypeReached#REACHED} or {@link TypeReached#UNTRACKED}, and {@link TypeReached#UNTRACKED}
 * types' super types must be {@link TypeReached#UNTRACKED}. This is verified in
 * <code>com.oracle.svm.hosted.meta.UniverseBuilder#checkHierarchyForTypeReachedConstraints</code>.
 */
@InternalVMMethod
public final class ClassInitializationInfo {

    /*
     * The singletons are here to reduce image size for build-time initialized classes that are
     * UNTRACKED for type reached.
     */
    private static final ClassInitializationInfo NO_INITIALIZER_NO_TRACKING = new ClassInitializationInfo(InitState.FullyInitialized, false, true, false);
    private static final ClassInitializationInfo INITIALIZED_NO_TRACKING = new ClassInitializationInfo(InitState.FullyInitialized, true, true, false);
    private static final ClassInitializationInfo FAILED_NO_TRACKING = new ClassInitializationInfo(InitState.InitializationError, false);

    public static ClassInitializationInfo forFailedInfo(boolean typeReachedTracked) {
        if (typeReachedTracked) {
            return new ClassInitializationInfo(InitState.InitializationError, typeReachedTracked);
        } else {
            return FAILED_NO_TRACKING;
        }
    }

    /**
     * Singleton for classes that are already initialized during image building and do not need
     * class initialization at runtime, and don't have {@code <clinit>} methods.
     */
    public static ClassInitializationInfo forNoInitializerInfo(boolean typeReachedTracked) {
        if (typeReachedTracked) {
            return new ClassInitializationInfo(InitState.FullyInitialized, false, true, typeReachedTracked);
        } else {
            return NO_INITIALIZER_NO_TRACKING;
        }
    }

    /**
     * For classes that are already initialized during image building and do not need class
     * initialization at runtime, but have {@code <clinit>} methods.
     */
    public static ClassInitializationInfo forInitializedInfo(boolean typeReachedTracked) {
        if (typeReachedTracked) {
            return new ClassInitializationInfo(InitState.FullyInitialized, true, true, typeReachedTracked);
        } else {
            return INITIALIZED_NO_TRACKING;
        }
    }

    public boolean requiresSlowPath() {
        return slowPathRequired;
    }

    public enum InitState {
        /**
         * Successfully linked/verified (but not initialized yet). Linking happens during image
         * building, so we do not need to track states before linking.
         */
        Linked,
        /** Currently running class initializer. */
        BeingInitialized,
        /** Initialized (successful final state). */
        FullyInitialized,
        /** Error happened during initialization. */
        InitializationError
    }

    public enum TypeReached {
        NOT_REACHED,
        REACHED,
        UNTRACKED,
    }

    interface ClassInitializerFunctionPointer extends CFunctionPointer {
        @InvokeJavaFunctionPointer
        void invoke();
    }

    /**
     * Function pointer to the class initializer, or null if the class does not have a class
     * initializer.
     */
    private final FunctionPointerHolder classInitializer;

    /**
     * Marks that this class has been reached at run time.
     */
    private TypeReached typeReached;

    /**
     * The current initialization state.
     */
    private InitState initState;

    /**
     * Requires one less check after lowering {@link EnsureClassInitializedNode}. <code>true</code>
     * if class is not build-time initialized or <code>typeReached != TypeReached.UNTRACKED</code>.
     */
    private boolean slowPathRequired;

    /**
     * The thread that is currently initializing the class. We use the platform thread instead of a
     * potential virtual thread because initializers like that of {@code sun.nio.ch.Poller} can
     * switch to the carrier thread and encounter the class that is being initialized again and
     * would wait for its initialization in the virtual thread to complete and therefore deadlock.
     * <p>
     * We also pin the virtual thread because it must not continue initialization on a different
     * platform thread, and also because if the platform thread switches to a different virtual
     * thread which encounters the class being initialized, it would wrongly be considered reentrant
     * initialization and enable use of the incompletely initialized class.
     */
    private IsolateThread initThread;

    /**
     * The lock held during initialization of the class. Allocated during image building, otherwise
     * we would need synchronization or atomic operations to install the lock at runtime.
     */
    private final ReentrantLock initLock;
    /**
     * The condition used to wait when class initialization is requested by multiple threads at the
     * same time. Lazily initialized without races because we are holding {@link #initLock} already.
     */
    private Condition initCondition;

    /**
     * Indicates if the class has a {@code <clinit>} method, no matter if it should be initialized
     * at native image's build time or run time.
     */
    private boolean hasInitializer;
    private boolean buildTimeInitialized;

    public boolean isTypeReached(DynamicHub caller) {
        assert typeReached != TypeReached.UNTRACKED : "We should never emit a check for untracked types as this was known at build time: " + caller.getName();
        return typeReached == TypeReached.REACHED;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public TypeReached getTypeReached() {
        return typeReached;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public void setTypeReached() {
        VMError.guarantee(typeReached != TypeReached.UNTRACKED, "Must not modify untracked types as nodes for checks have already been omitted.");
        typeReached = TypeReached.REACHED;
        slowPathRequired = initState != InitState.FullyInitialized;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public ClassInitializationInfo(InitState initState, boolean hasInitializer, boolean buildTimeInitialized, boolean typeReachedTracked) {
        this(initState, typeReachedTracked);
        this.hasInitializer = hasInitializer;
        this.buildTimeInitialized = buildTimeInitialized;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    private ClassInitializationInfo(InitState initState, boolean typeReachedTracked) {
        this.classInitializer = null;
        this.typeReached = typeReachedTracked ? TypeReached.NOT_REACHED : TypeReached.UNTRACKED;
        this.initState = initState;
        this.slowPathRequired = typeReached != TypeReached.UNTRACKED || initState != InitState.FullyInitialized;
        this.initLock = initState == InitState.FullyInitialized ? null : new ReentrantLock();
        this.hasInitializer = true;
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public ClassInitializationInfo(CFunctionPointer classInitializer, boolean typeReachedTracked) {
        this.classInitializer = classInitializer == null || classInitializer.isNull() ? null : new FunctionPointerHolder(classInitializer);
        this.initState = InitState.Linked;
        this.typeReached = typeReachedTracked ? TypeReached.NOT_REACHED : TypeReached.UNTRACKED;
        this.slowPathRequired = true;
        this.initLock = new ReentrantLock();
        this.hasInitializer = classInitializer != null;
    }

    public ClassInitializationInfo(boolean typeReachedTracked) {
        assert RuntimeClassLoading.isSupported();

        this.classInitializer = null;
        this.hasInitializer = true;

        // GR-59739: Needs a new state "Loaded".
        this.initState = InitState.Linked;
        this.typeReached = typeReachedTracked ? TypeReached.NOT_REACHED : TypeReached.UNTRACKED;
        this.slowPathRequired = true;
        this.initLock = new ReentrantLock();
    }

    public boolean hasInitializer() {
        return hasInitializer;
    }

    public boolean isInitialized() {
        return initState == InitState.FullyInitialized;
    }

    public boolean isInitializationError() {
        return initState == InitState.InitializationError;
    }

    public boolean isBuildTimeInitialized() {
        return buildTimeInitialized;
    }

    private boolean isBeingInitialized() {
        return initState == InitState.BeingInitialized;
    }

    public boolean isInErrorState() {
        return initState == InitState.InitializationError;
    }

    public boolean isLinked() {
        return initState == InitState.Linked;
    }

    public boolean isTracked() {
        return typeReached != TypeReached.UNTRACKED;
    }

    public FunctionPointerHolder getClassInitializer() {
        return classInitializer;
    }

    private boolean isReentrantInitialization(IsolateThread thread) {
        return thread.equal(initThread);
    }

    /**
     * Marks the hierarchy of <code>hub</code> as reached.
     * <p>
     * Locking is not needed as the whole type hierarchy (up until
     * <code>TypeReached.UNTRACKED</code> types) is marked as reached every time we enter the class
     * initialization slow path. The hierarchy of a type can be marked as reached multiple times in
     * following cases:
     * <ul>
     * <li>Every time we reach type initialization when the type is in the
     * {@link InitState#InitializationError} state</li>
     * <li>Multiple times by different threads while the type is being initialized by one
     * thread.</li>
     * </ul>
     * 
     */
    private static void markReached(DynamicHub hub) {
        var current = hub;
        do {
            ClassInitializationInfo clinitInfo = current.getClassInitializationInfo();
            if (clinitInfo.typeReached == TypeReached.UNTRACKED) {
                break;
            }
            clinitInfo.typeReached = TypeReached.REACHED;
            if (clinitInfo.isInitialized()) {
                clinitInfo.slowPathRequired = false;
            }
            reachInterfaces(current);

            current = current.getSuperHub();
        } while (current != null);
    }

    private static void reachInterfaces(DynamicHub hub) {
        for (DynamicHub superInterface : hub.getInterfaces()) {
            if (superInterface.getClassInitializationInfo().typeReached == TypeReached.REACHED) {
                return;
            }

            if (hub.getClassInitializationInfo().typeReached != TypeReached.UNTRACKED) {
                superInterface.getClassInitializationInfo().typeReached = TypeReached.REACHED;
            }
            reachInterfaces(superInterface);
        }
    }

    /**
     * Perform class initialization. This is the slow-path that should only be called after checking
     * {@link #isInitialized}.
     * </p>
     * Steps refer to the
     * <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.5">JVM
     * specification for class initialization</a>.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = true)
    private static void slowPath(ClassInitializationInfo info, DynamicHub hub) {
        IsolateThread self = CurrentIsolate.getCurrentThread();

        /*
         * Types are marked as reached before any initialization is performed. Reason: the results
         * should be visible in class initializers of the whole hierarchy as they could use
         * reflection.
         */
        markReached(hub);

        if (info.isInitialized()) {
            return;
        }

        /*
         * GR-43118: If a predefined class is not loaded, and the caller class is loaded, set the
         * classloader of the initialized class to the class loader of the caller class.
         * 
         * This does not work in general as class loading happens in more places than class
         * initialization, e.g., on class literals. However, this workaround makes most of the cases
         * work until we have a proper implementation of class loading.
         */
        if (!hub.isLoaded()) {
            Class<?> callerClass = Reflection.getCallerClass();
            if (DynamicHub.fromClass(callerClass).isLoaded()) {
                PredefinedClassesSupport.loadClassIfNotLoaded(callerClass.getClassLoader(), null, DynamicHub.toClass(hub));
            }
        }

        /*
         * Step 1: Synchronize on the initialization lock, LC, for C. This involves waiting until
         * the current thread can acquire LC
         */
        info.initLock.lock();
        try {
            /*
             * Step 2: If the Class object for C indicates that initialization is in progress for C
             * by some other thread, then release LC and block the current thread until informed
             * that the in-progress initialization has completed, at which time repeat this
             * procedure.
             *
             * Thread interrupt status is unaffected by execution of the initialization procedure.
             */
            while (info.isBeingInitialized() && !info.isReentrantInitialization(self)) {
                if (info.initCondition == null) {
                    /*
                     * We are holding initLock, so there cannot be any races installing the
                     * initCondition.
                     */
                    info.initCondition = info.initLock.newCondition();
                }
                info.initCondition.awaitUninterruptibly();
            }

            /*
             * Step 3: If the Class object for C indicates that initialization is in progress for C
             * by the current thread, then this must be a recursive request for initialization.
             * Release LC and complete normally.
             */
            if (info.isBeingInitialized() && info.isReentrantInitialization(self)) {
                return;
            }

            /*
             * Step 4: If the Class object for C indicates that C has already been initialized, then
             * no further action is required. Release LC and complete normally.
             */
            if (info.isInitialized()) {
                return;
            }

            /*
             * Step 5: If the Class object for C is in an erroneous state, then initialization is
             * not possible. Release LC and throw a NoClassDefFoundError.
             */
            if (info.isInErrorState()) {
                throw new NoClassDefFoundError("Could not initialize class " + hub.getName());
            }

            /*
             * Step 6: Record the fact that initialization of the Class object for C is in progress
             * by the current thread, and release LC.
             */
            info.initState = InitState.BeingInitialized;
            info.initThread = self;

        } finally {
            info.initLock.unlock();
        }

        boolean pinned = false;
        if (ContinuationSupport.isSupported() && JavaThreads.isCurrentThreadVirtual()) {
            // See comment on field `initThread`
            Target_jdk_internal_vm_Continuation.pin();
            pinned = true;
        }
        try {
            doInitialize(info, hub);
        } finally {
            if (pinned) {
                Target_jdk_internal_vm_Continuation.unpin();
            }
        }
    }

    private static void doInitialize(ClassInitializationInfo info, DynamicHub hub) {
        /*
         * Step 7: Next, if C is a class rather than an interface, initialize its super class and
         * super interfaces.
         */
        if (!hub.isInterface()) {
            try {
                if (hub.getSuperHub() != null) {
                    hub.getSuperHub().ensureInitialized();
                }
                /*
                 * If C implements any interfaces that declares a non-abstract, non-static method,
                 * the initialization of C triggers initialization of its super interfaces.
                 *
                 * Only need to recurse if hasDefaultMethods is set, which includes declaring and
                 * inheriting default methods.
                 */
                if (hub.hasDefaultMethods()) {
                    initializeSuperInterfaces(hub);
                }
            } catch (Throwable ex) {
                /*
                 * If the initialization of S completes abruptly because of a thrown exception, then
                 * acquire LC, label the Class object for C as erroneous, notify all waiting
                 * threads, release LC, and complete abruptly, throwing the same exception that
                 * resulted from initializing SC.
                 */
                info.setInitializationStateAndNotify(InitState.InitializationError);
                throw ex;
            }
        }

        /*
         * Step 8: Next, determine whether assertions are enabled for C by querying its defining
         * class loader.
         *
         * Nothing to do for this step, Substrate VM fixes the assertion status during image
         * building.
         */

        Throwable exception = null;
        try {
            /* Step 9: Next, execute the class or interface initialization method of C. */
            info.invokeClassInitializer(hub);
        } catch (Throwable ex) {
            exception = ex;
        }

        if (exception == null) {
            /*
             * Step 10: If the execution of the class or interface initialization method completes
             * normally, then acquire LC, label the Class object for C as fully initialized, notify
             * all waiting threads, release LC, and complete this procedure normally.
             */
            info.setInitializationStateAndNotify(InitState.FullyInitialized);
        } else {
            /*
             * Step 11: Otherwise, the class or interface initialization method must have completed
             * abruptly by throwing some exception E. If the class of E is not Error or one of its
             * subclasses, then create a new instance of the class ExceptionInInitializerError with
             * E as the argument, and use this object in place of E in the following step.
             */
            if (!(exception instanceof Error)) {
                exception = new ExceptionInInitializerError(exception);
            }
            /*
             * Step 12: Acquire LC, label the Class object for C as erroneous, notify all waiting
             * threads, release LC, and complete this procedure abruptly with reason E or its
             * replacement as determined in the previous step.
             */
            info.setInitializationStateAndNotify(InitState.InitializationError);
            throw (Error) exception;
        }
    }

    /**
     * Eagerly initialize superinterfaces that declare default methods.
     */
    private static void initializeSuperInterfaces(DynamicHub hub) {
        assert hub.hasDefaultMethods() : "caller should have checked this";
        for (DynamicHub iface : hub.getInterfaces()) {
            /*
             * Initialization is depth first search, i.e., we start with top of the inheritance
             * tree. hasDefaultMethods drives searching superinterfaces since it means
             * hasDefaultMethods in its superinterface hierarchy.
             */
            if (iface.hasDefaultMethods()) {
                initializeSuperInterfaces(iface);
            }

            /*
             * Only initialize interfaces that "declare" concrete methods. This logic follows the
             * implementation in the Java HotSpot VM, it does not seem to be mentioned in the Java
             * VM specification.
             */
            if (iface.declaresDefaultMethods()) {
                iface.ensureInitialized();
            }
        }
    }

    /**
     * Acquire lock, set state, and notify all waiting threads.
     */
    private void setInitializationStateAndNotify(InitState state) {
        initLock.lock();
        try {
            this.initState = state;
            if (initState == InitState.FullyInitialized) {
                this.slowPathRequired = false;
            }
            this.initThread = Word.nullPointer();
            /* Make sure previous stores are all done, notably the initState. */
            Unsafe.getUnsafe().storeFence();

            if (initCondition != null) {
                initCondition.signalAll();
                initCondition = null;
            }
        } finally {
            initLock.unlock();
        }
    }

    private void invokeClassInitializer(DynamicHub hub) {
        if (classInitializer != null) {
            ClassInitializerFunctionPointer functionPointer = (ClassInitializerFunctionPointer) classInitializer.functionPointer;
            if (functionPointer.isNull()) {
                throw invokeClassInitializerError(hub);
            }
            functionPointer.invoke();
        }
    }

    private static RuntimeException invokeClassInitializerError(DynamicHub hub) {
        throw VMError.shouldNotReachHere("No classInitializer.functionPointer for class " + hub.getName());
    }
}
