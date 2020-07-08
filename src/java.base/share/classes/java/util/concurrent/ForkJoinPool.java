/*
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


/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.AccessControlContext;
import java.security.Permission;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.locks.LockSupport;

/**
 * An {@link ExecutorService} for running {@link ForkJoinTask}s.
 * A {@code ForkJoinPool} provides the entry point for submissions
 * from non-{@code ForkJoinTask} clients, as well as management and
 * monitoring operations.
 *
 * <p>A {@code ForkJoinPool} differs from other kinds of {@link
 * ExecutorService} mainly by virtue of employing
 * <em>work-stealing</em>: all threads in the pool attempt to find and
 * execute tasks submitted to the pool and/or created by other active
 * tasks (eventually blocking waiting for work if none exist). This
 * enables efficient processing when most tasks spawn other subtasks
 * (as do most {@code ForkJoinTask}s), as well as when many small
 * tasks are submitted to the pool from external clients.  Especially
 * when setting <em>asyncMode</em> to true in constructors, {@code
 * ForkJoinPool}s may also be appropriate for use with event-style
 * tasks that are never joined. All worker threads are initialized
 * with {@link Thread#isDaemon} set {@code true}.
 *
 * <p>A static {@link #commonPool()} is available and appropriate for
 * most applications. The common pool is used by any ForkJoinTask that
 * is not explicitly submitted to a specified pool. Using the common
 * pool normally reduces resource usage (its threads are slowly
 * reclaimed during periods of non-use, and reinstated upon subsequent
 * use).
 *
 * <p>For applications that require separate or custom pools, a {@code
 * ForkJoinPool} may be constructed with a given target parallelism
 * level; by default, equal to the number of available processors.
 * The pool attempts to maintain enough active (or available) threads
 * by dynamically adding, suspending, or resuming internal worker
 * threads, even if some tasks are stalled waiting to join others.
 * However, no such adjustments are guaranteed in the face of blocked
 * I/O or other unmanaged synchronization. The nested {@link
 * ManagedBlocker} interface enables extension of the kinds of
 * synchronization accommodated. The default policies may be
 * overridden using a constructor with parameters corresponding to
 * those documented in class {@link ThreadPoolExecutor}.
 *
 * <p>In addition to execution and lifecycle control methods, this
 * class provides status check methods (for example
 * {@link #getStealCount}) that are intended to aid in developing,
 * tuning, and monitoring fork/join applications. Also, method
 * {@link #toString} returns indications of pool state in a
 * convenient form for informal monitoring.
 *
 * <p>As is the case with other ExecutorServices, there are three
 * main task execution methods summarized in the following table.
 * These are designed to be used primarily by clients not already
 * engaged in fork/join computations in the current pool.  The main
 * forms of these methods accept instances of {@code ForkJoinTask},
 * but overloaded forms also allow mixed execution of plain {@code
 * Runnable}- or {@code Callable}- based activities as well.  However,
 * tasks that are already executing in a pool should normally instead
 * use the within-computation forms listed in the table unless using
 * async event-style tasks that are not usually joined, in which case
 * there is little difference among choice of methods.
 *
 * <table class="plain">
 * <caption>Summary of task execution methods</caption>
 *  <tr>
 *    <td></td>
 *    <th scope="col"> Call from non-fork/join clients</th>
 *    <th scope="col"> Call from within fork/join computations</th>
 *  </tr>
 *  <tr>
 *    <th scope="row" style="text-align:left"> Arrange async execution</th>
 *    <td> {@link #execute(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork}</td>
 *  </tr>
 *  <tr>
 *    <th scope="row" style="text-align:left"> Await and obtain result</th>
 *    <td> {@link #invoke(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#invoke}</td>
 *  </tr>
 *  <tr>
 *    <th scope="row" style="text-align:left"> Arrange exec and obtain Future</th>
 *    <td> {@link #submit(ForkJoinTask)}</td>
 *    <td> {@link ForkJoinTask#fork} (ForkJoinTasks <em>are</em> Futures)</td>
 *  </tr>
 * </table>
 *
 * <p>The parameters used to construct the common pool may be controlled by
 * setting the following {@linkplain System#getProperty system properties}:
 * <ul>
 * <li>{@systemProperty java.util.concurrent.ForkJoinPool.common.parallelism}
 * - the parallelism level, a non-negative integer
 * <li>{@systemProperty java.util.concurrent.ForkJoinPool.common.threadFactory}
 * - the class name of a {@link ForkJoinWorkerThreadFactory}.
 * The {@linkplain ClassLoader#getSystemClassLoader() system class loader}
 * is used to load this class.
 * <li>{@systemProperty java.util.concurrent.ForkJoinPool.common.exceptionHandler}
 * - the class name of a {@link UncaughtExceptionHandler}.
 * The {@linkplain ClassLoader#getSystemClassLoader() system class loader}
 * is used to load this class.
 * <li>{@systemProperty java.util.concurrent.ForkJoinPool.common.maximumSpares}
 * - the maximum number of allowed extra threads to maintain target
 * parallelism (default 256).
 * </ul>
 * If no thread factory is supplied via a system property, then the
 * common pool uses a factory that uses the system class loader as the
 * {@linkplain Thread#getContextClassLoader() thread context class loader}.
 * In addition, if a {@link SecurityManager} is present, then
 * the common pool uses a factory supplying threads that have no
 * {@link Permissions} enabled.
 *
 * Upon any error in establishing these settings, default parameters
 * are used. It is possible to disable or limit the use of threads in
 * the common pool by setting the parallelism property to zero, and/or
 * using a factory that may return {@code null}. However doing so may
 * cause unjoined tasks to never be executed.
 *
 * <p><b>Implementation notes</b>: This implementation restricts the
 * maximum number of running threads to 32767. Attempts to create
 * pools with greater than the maximum number result in
 * {@code IllegalArgumentException}.
 *
 * <p>This implementation rejects submitted tasks (that is, by throwing
 * {@link RejectedExecutionException}) only when the pool is shut down
 * or internal resources have been exhausted.
 *
 * @since 1.7
 * @author Doug Lea
 */
public class ForkJoinPool extends AbstractExecutorService {

    /*
     * Implementation Overview
     *
     * This class and its nested classes provide the main
     * functionality and control for a set of worker threads:
     * Submissions from non-FJ threads enter into submission queues.
     * Workers take these tasks and typically split them into subtasks
     * that may be stolen by other workers. Work-stealing based on
     * randomized scans generally leads to better throughput than
     * "work dealing" in which producers assign tasks to idle threads,
     * in part because threads that have finished other tasks before
     * the signalled thread wakes up (which can be a long time) can
     * take the task instead.  Preference rules give first priority to
     * processing tasks from their own queues (LIFO or FIFO, depending
     * on mode), then to randomized FIFO steals of tasks in other
     * queues.  This framework began as vehicle for supporting
     * tree-structured parallelism using work-stealing.  Over time,
     * its scalability advantages led to extensions and changes to
     * better support more diverse usage contexts.  Because most
     * internal methods and nested classes are interrelated, their
     * main rationale and descriptions are presented here; individual
     * methods and nested classes contain only brief comments about
     * details.
     *
     * WorkQueues
     * ==========
     *
     * Most operations occur within work-stealing queues (in nested
     * class WorkQueue).  These are special forms of Deques that
     * support only three of the four possible end-operations -- push,
     * pop, and poll (aka steal), under the further constraints that
     * push and pop are called only from the owning thread (or, as
     * extended here, under a lock), while poll may be called from
     * other threads.  (If you are unfamiliar with them, you probably
     * want to read Herlihy and Shavit's book "The Art of
     * Multiprocessor programming", chapter 16 describing these in
     * more detail before proceeding.)  The main work-stealing queue
     * design is roughly similar to those in the papers "Dynamic
     * Circular Work-Stealing Deque" by Chase and Lev, SPAA 2005
     * (http://research.sun.com/scalable/pubs/index.html) and
     * "Idempotent work stealing" by Michael, Saraswat, and Vechev,
     * PPoPP 2009 (http://portal.acm.org/citation.cfm?id=1504186).
     * The main differences ultimately stem from GC requirements that
     * we null out taken slots as soon as we can, to maintain as small
     * a footprint as possible even in programs generating huge
     * numbers of tasks. To accomplish this, we shift the CAS
     * arbitrating pop vs poll (steal) from being on the indices
     * ("base" and "top") to the slots themselves.
     *
     * Adding tasks then takes the form of a classic array push(task)
     * in a circular buffer:
     *    q.array[q.top++ % length] = task;
     *
     * (The actual code needs to null-check and size-check the array,
     * uses masking, not mod, for indexing a power-of-two-sized array,
     * adds a release fence for publication, and possibly signals
     * waiting workers to start scanning -- see below.)  Both a
     * successful pop and poll mainly entail a CAS of a slot from
     * non-null to null.
     *
     * The pop operation (always performed by owner) is:
     *   if ((the task at top slot is not null) and
     *        (CAS slot to null))
     *           decrement top and return task;
     *
     * And the poll operation (usually by a stealer) is
     *    if ((the task at base slot is not null) and
     *        (CAS slot to null))
     *           increment base and return task;
     *
     * There are several variants of each of these. Most uses occur
     * within operations that also interleave contention or emptiness
     * tracking or inspection of elements before extracting them, so
     * must interleave these with the above code. When performed by
     * owner, getAndSet is used instead of CAS (see for example method
     * nextLocalTask) which is usually more efficient, and possible
     * because the top index cannot independently change during the
     * operation.
     *
     * Memory ordering.  See "Correct and Efficient Work-Stealing for
     * Weak Memory Models" by Le, Pop, Cohen, and Nardelli, PPoPP 2013
     * (http://www.di.ens.fr/~zappa/readings/ppopp13.pdf) for an
     * analysis of memory ordering requirements in work-stealing
     * algorithms similar to (but different than) the one used here.
     * Extracting tasks in array slots via (fully fenced) CAS provides
     * primary synchronization. The base and top indices imprecisely
     * guide where to extract from. We do not usually require strict
     * orderings of array and index updates. Many index accesses use
     * plain mode, with ordering constrained by surrounding context
     * (usually with respect to element CASes or the two WorkQueue
     * volatile fields source and phase). When not otherwise already
     * constrained, reads of "base" by queue owners use acquire-mode,
     * and some externally callable methods preface accesses with
     * acquire fences.  Additionally, to ensure that index update
     * writes are not coalesced or postponed in loops etc, "opaque"
     * mode is used in a few cases where timely writes are not
     * otherwise ensured. The "locked" versions of push- and pop-
     * based methods for shared queues differ from owned versions
     * because locking already forces some of the ordering.
     *
     * Because indices and slot contents cannot always be consistent,
     * a check that base == top indicates (momentary) emptiness, but
     * otherwise may err on the side of possibly making the queue
     * appear nonempty when a push, pop, or poll have not fully
     * committed, or making it appear empty when an update of top has
     * not yet been visibly written.  (Method isEmpty() checks the
     * case of a partially completed removal of the last element.)
     * Because of this, the poll operation, considered individually,
     * is not wait-free. One thief cannot successfully continue until
     * another in-progress one (or, if previously empty, a push)
     * visibly completes.  This can stall threads when required to
     * consume from a given queue (see method poll()).  However, in
     * the aggregate, we ensure at least probabilistic
     * non-blockingness.  If an attempted steal fails, a scanning
     * thief chooses a different random victim target to try next. So,
     * in order for one thief to progress, it suffices for any
     * in-progress poll or new push on any empty queue to complete.
     *
     * This approach also enables support of a user mode in which
     * local task processing is in FIFO, not LIFO order, simply by
     * using poll rather than pop.  This can be useful in
     * message-passing frameworks in which tasks are never joined.
     *
     * WorkQueues are also used in a similar way for tasks submitted
     * to the pool. We cannot mix these tasks in the same queues used
     * by workers. Instead, we randomly associate submission queues
     * with submitting threads, using a form of hashing.  The
     * ThreadLocalRandom probe value serves as a hash code for
     * choosing existing queues, and may be randomly repositioned upon
     * contention with other submitters.  In essence, submitters act
     * like workers except that they are restricted to executing local
     * tasks that they submitted.  Insertion of tasks in shared mode
     * requires a lock but we use only a simple spinlock (using field
     * phase), because submitters encountering a busy queue move to a
     * different position to use or create other queues -- they block
     * only when creating and registering new queues. Because it is
     * used only as a spinlock, unlocking requires only a "releasing"
     * store (using setRelease) unless otherwise signalling.
     *
     * Management
     * ==========
     *
     * The main throughput advantages of work-stealing stem from
     * decentralized control -- workers mostly take tasks from
     * themselves or each other, at rates that can exceed a billion
     * per second.  The pool itself creates, activates (enables
     * scanning for and running tasks), deactivates, blocks, and
     * terminates threads, all with minimal central information.
     * There are only a few properties that we can globally track or
     * maintain, so we pack them into a small number of variables,
     * often maintaining atomicity without blocking or locking.
     * Nearly all essentially atomic control state is held in a few
     * volatile variables that are by far most often read (not
     * written) as status and consistency checks. We pack as much
     * information into them as we can.
     *
     * Field "ctl" contains 64 bits holding information needed to
     * atomically decide to add, enqueue (on an event queue), and
     * dequeue and release workers.  To enable this packing, we
     * restrict maximum parallelism to (1<<15)-1 (which is far in
     * excess of normal operating range) to allow ids, counts, and
     * their negations (used for thresholding) to fit into 16bit
     * subfields.
     *
     * Field "mode" holds configuration parameters as well as lifetime
     * status, atomically and monotonically setting SHUTDOWN, STOP,
     * and finally TERMINATED bits.
     *
     * Field "workQueues" holds references to WorkQueues.  It is
     * updated (only during worker creation and termination) under
     * lock (using field workerNamePrefix as lock), but is otherwise
     * concurrently readable, and accessed directly. We also ensure
     * that uses of the array reference itself never become too stale
     * in case of resizing, by arranging that (re-)reads are separated
     * by at least one acquiring read access.  To simplify index-based
     * operations, the array size is always a power of two, and all
     * readers must tolerate null slots. Worker queues are at odd
     * indices. Shared (submission) queues are at even indices, up to
     * a maximum of 64 slots, to limit growth even if the array needs
     * to expand to add more workers. Grouping them together in this
     * way simplifies and speeds up task scanning.
     *
     * All worker thread creation is on-demand, triggered by task
     * submissions, replacement of terminated workers, and/or
     * compensation for blocked workers. However, all other support
     * code is set up to work with other policies.  To ensure that we
     * do not hold on to worker references that would prevent GC, all
     * accesses to workQueues are via indices into the workQueues
     * array (which is one source of some of the messy code
     * constructions here). In essence, the workQueues array serves as
     * a weak reference mechanism. Thus for example the stack top
     * subfield of ctl stores indices, not references.
     *
     * Queuing Idle Workers. Unlike HPC work-stealing frameworks, we
     * cannot let workers spin indefinitely scanning for tasks when
     * none can be found immediately, and we cannot start/resume
     * workers unless there appear to be tasks available.  On the
     * other hand, we must quickly prod them into action when new
     * tasks are submitted or generated. In many usages, ramp-up time
     * is the main limiting factor in overall performance, which is
     * compounded at program start-up by JIT compilation and
     * allocation. So we streamline this as much as possible.
     *
     * The "ctl" field atomically maintains total worker and
     * "released" worker counts, plus the head of the available worker
     * queue (actually stack, represented by the lower 32bit subfield
     * of ctl).  Released workers are those known to be scanning for
     * and/or running tasks. Unreleased ("available") workers are
     * recorded in the ctl stack. These workers are made available for
     * signalling by enqueuing in ctl (see method runWorker).  The
     * "queue" is a form of Treiber stack. This is ideal for
     * activating threads in most-recently used order, and improves
     * performance and locality, outweighing the disadvantages of
     * being prone to contention and inability to release a worker
     * unless it is topmost on stack.  To avoid missed signal problems
     * inherent in any wait/signal design, available workers rescan
     * for (and if found run) tasks after enqueuing.  Normally their
     * release status will be updated while doing so, but the released
     * worker ctl count may underestimate the number of active
     * threads. (However, it is still possible to determine quiescence
     * via a validation traversal -- see isQuiescent).  After an
     * unsuccessful rescan, available workers are blocked until
     * signalled (see signalWork).  The top stack state holds the
     * value of the "phase" field of the worker: its index and status,
     * plus a version counter that, in addition to the count subfields
     * (also serving as version stamps) provide protection against
     * Treiber stack ABA effects.
     *
     * Creating workers. To create a worker, we pre-increment counts
     * (serving as a reservation), and attempt to construct a
     * ForkJoinWorkerThread via its factory. Upon construction, the
     * new thread invokes registerWorker, where it constructs a
     * WorkQueue and is assigned an index in the workQueues array
     * (expanding the array if necessary). The thread is then started.
     * Upon any exception across these steps, or null return from
     * factory, deregisterWorker adjusts counts and records
     * accordingly.  If a null return, the pool continues running with
     * fewer than the target number workers. If exceptional, the
     * exception is propagated, generally to some external caller.
     * Worker index assignment avoids the bias in scanning that would
     * occur if entries were sequentially packed starting at the front
     * of the workQueues array. We treat the array as a simple
     * power-of-two hash table, expanding as needed. The seedIndex
     * increment ensures no collisions until a resize is needed or a
     * worker is deregistered and replaced, and thereafter keeps
     * probability of collision low. We cannot use
     * ThreadLocalRandom.getProbe() for similar purposes here because
     * the thread has not started yet, but do so for creating
     * submission queues for existing external threads (see
     * externalPush).
     *
     * WorkQueue field "phase" is used by both workers and the pool to
     * manage and track whether a worker is UNSIGNALLED (possibly
     * blocked waiting for a signal).  When a worker is enqueued its
     * phase field is set. Note that phase field updates lag queue CAS
     * releases so usage requires care -- seeing a negative phase does
     * not guarantee that the worker is available. When queued, the
     * lower 16 bits of scanState must hold its pool index. So we
     * place the index there upon initialization and otherwise keep it
     * there or restore it when necessary.
     *
     * The ctl field also serves as the basis for memory
     * synchronization surrounding activation. This uses a more
     * efficient version of a Dekker-like rule that task producers and
     * consumers sync with each other by both writing/CASing ctl (even
     * if to its current value).  This would be extremely costly. So
     * we relax it in several ways: (1) Producers only signal when
     * their queue is possibly empty at some point during a push
     * operation. (2) Other workers propagate this signal
     * when they find tasks in a queue with size greater than one. (3)
     * Workers only enqueue after scanning (see below) and not finding
     * any tasks.  (4) Rather than CASing ctl to its current value in
     * the common case where no action is required, we reduce write
     * contention by equivalently prefacing signalWork when called by
     * an external task producer using a memory access with
     * full-volatile semantics or a "fullFence".
     *
     * Almost always, too many signals are issued, in part because a
     * task producer cannot tell if some existing worker is in the
     * midst of finishing one task (or already scanning) and ready to
     * take another without being signalled. So the producer might
     * instead activate a different worker that does not find any
     * work, and then inactivates. This scarcely matters in
     * steady-state computations involving all workers, but can create
     * contention and bookkeeping bottlenecks during ramp-up,
     * ramp-down, and small computations involving only a few workers.
     *
     * Scanning. Method scan (from runWorker) performs top-level
     * scanning for tasks. (Similar scans appear in helpQuiesce and
     * pollScan.)  Each scan traverses and tries to poll from each
     * queue starting at a random index. Scans are not performed in
     * ideal random permutation order, to reduce cacheline
     * contention. The pseudorandom generator need not have
     * high-quality statistical properties in the long term, but just
     * within computations; We use Marsaglia XorShifts (often via
     * ThreadLocalRandom.nextSecondarySeed), which are cheap and
     * suffice. Scanning also includes contention reduction: When
     * scanning workers fail to extract an apparently existing task,
     * they soon restart at a different pseudorandom index.  This form
     * of backoff improves throughput when many threads are trying to
     * take tasks from few queues, which can be common in some usages.
     * Scans do not otherwise explicitly take into account core
     * affinities, loads, cache localities, etc, However, they do
     * exploit temporal locality (which usually approximates these) by
     * preferring to re-poll from the same queue after a successful
     * poll before trying others (see method topLevelExec). However
     * this preference is bounded (see TOP_BOUND_SHIFT) as a safeguard
     * against infinitely unfair looping under unbounded user task
     * recursion, and also to reduce long-term contention when many
     * threads poll few queues holding many small tasks. The bound is
     * high enough to avoid much impact on locality and scheduling
     * overhead.
     *
     * Trimming workers. To release resources after periods of lack of
     * use, a worker starting to wait when the pool is quiescent will
     * time out and terminate (see method runWorker) if the pool has
     * remained quiescent for period given by field keepAlive.
     *
     * Shutdown and Termination. A call to shutdownNow invokes
     * tryTerminate to atomically set a runState bit. The calling
     * thread, as well as every other worker thereafter terminating,
     * helps terminate others by cancelling their unprocessed tasks,
     * and waking them up, doing so repeatedly until stable. Calls to
     * non-abrupt shutdown() preface this by checking whether
     * termination should commence by sweeping through queues (until
     * stable) to ensure lack of in-flight submissions and workers
     * about to process them before triggering the "STOP" phase of
     * termination.
     *
     * Joining Tasks
     * =============
     *
     * Any of several actions may be taken when one worker is waiting
     * to join a task stolen (or always held) by another.  Because we
     * are multiplexing many tasks on to a pool of workers, we can't
     * always just let them block (as in Thread.join).  We also cannot
     * just reassign the joiner's run-time stack with another and
     * replace it later, which would be a form of "continuation", that
     * even if possible is not necessarily a good idea since we may
     * need both an unblocked task and its continuation to progress.
     * Instead we combine two tactics:
     *
     *   Helping: Arranging for the joiner to execute some task that it
     *      would be running if the steal had not occurred.
     *
     *   Compensating: Unless there are already enough live threads,
     *      method tryCompensate() may create or re-activate a spare
     *      thread to compensate for blocked joiners until they unblock.
     *
     * A third form (implemented in tryRemoveAndExec) amounts to
     * helping a hypothetical compensator: If we can readily tell that
     * a possible action of a compensator is to steal and execute the
     * task being joined, the joining thread can do so directly,
     * without the need for a compensation thread.
     *
     * The ManagedBlocker extension API can't use helping so relies
     * only on compensation in method awaitBlocker.
     *
     * The algorithm in awaitJoin entails a form of "linear helping".
     * Each worker records (in field source) the id of the queue from
     * which it last stole a task.  The scan in method awaitJoin uses
     * these markers to try to find a worker to help (i.e., steal back
     * a task from and execute it) that could hasten completion of the
     * actively joined task.  Thus, the joiner executes a task that
     * would be on its own local deque if the to-be-joined task had
     * not been stolen. This is a conservative variant of the approach
     * described in Wagner & Calder "Leapfrogging: a portable
     * technique for implementing efficient futures" SIGPLAN Notices,
     * 1993 (http://portal.acm.org/citation.cfm?id=155354). It differs
     * mainly in that we only record queue ids, not full dependency
     * links.  This requires a linear scan of the workQueues array to
     * locate stealers, but isolates cost to when it is needed, rather
     * than adding to per-task overhead. Searches can fail to locate
     * stealers GC stalls and the like delay recording sources.
     * Further, even when accurately identified, stealers might not
     * ever produce a task that the joiner can in turn help with. So,
     * compensation is tried upon failure to find tasks to run.
     *
     * Compensation does not by default aim to keep exactly the target
     * parallelism number of unblocked threads running at any given
     * time. Some previous versions of this class employed immediate
     * compensations for any blocked join. However, in practice, the
     * vast majority of blockages are transient byproducts of GC and
     * other JVM or OS activities that are made worse by replacement
     * when they cause longer-term oversubscription.  Rather than
     * impose arbitrary policies, we allow users to override the
     * default of only adding threads upon apparent starvation.  The
     * compensation mechanism may also be bounded.  Bounds for the
     * commonPool (see COMMON_MAX_SPARES) better enable JVMs to cope
     * with programming errors and abuse before running out of
     * resources to do so.
     *
     * Common Pool
     * ===========
     *
     * The static common pool always exists after static
     * initialization.  Since it (or any other created pool) need
     * never be used, we minimize initial construction overhead and
     * footprint to the setup of about a dozen fields.
     *
     * When external threads submit to the common pool, they can
     * perform subtask processing (see externalHelpComplete and
     * related methods) upon joins.  This caller-helps policy makes it
     * sensible to set common pool parallelism level to one (or more)
     * less than the total number of available cores, or even zero for
     * pure caller-runs.  We do not need to record whether external
     * submissions are to the common pool -- if not, external help
     * methods return quickly. These submitters would otherwise be
     * blocked waiting for completion, so the extra effort (with
     * liberally sprinkled task status checks) in inapplicable cases
     * amounts to an odd form of limited spin-wait before blocking in
     * ForkJoinTask.join.
     *
     * As a more appropriate default in managed environments, unless
     * overridden by system properties, we use workers of subclass
     * InnocuousForkJoinWorkerThread when there is a SecurityManager
     * present. These workers have no permissions set, do not belong
     * to any user-defined ThreadGroup, and erase all ThreadLocals
     * after executing any top-level task (see
     * WorkQueue.afterTopLevelExec).  The associated mechanics (mainly
     * in ForkJoinWorkerThread) may be JVM-dependent and must access
     * particular Thread class fields to achieve this effect.
     *
     * Memory placement
     * ================
     *
     * Performance can be very sensitive to placement of instances of
     * ForkJoinPool and WorkQueues and their queue arrays. To reduce
     * false-sharing impact, the @Contended annotation isolates
     * adjacent WorkQueue instances, as well as the ForkJoinPool.ctl
     * field. WorkQueue arrays are allocated (by their threads) with
     * larger initial sizes than most ever need, mostly to reduce
     * false sharing with current garbage collectors that use cardmark
     * tables.
     *
     * Style notes
     * ===========
     *
     * Memory ordering relies mainly on VarHandles.  This can be
     * awkward and ugly, but also reflects the need to control
     * outcomes across the unusual cases that arise in very racy code
     * with very few invariants. All fields are read into locals
     * before use, and null-checked if they are references.  Array
     * accesses using masked indices include checks (that are always
     * true) that the array length is non-zero to avoid compilers
     * inserting more expensive traps.  This is usually done in a
     * "C"-like style of listing declarations at the heads of methods
     * or blocks, and using inline assignments on first encounter.
     * Nearly all explicit checks lead to bypass/return, not exception
     * throws, because they may legitimately arise due to
     * cancellation/revocation during shutdown.
     *
     * There is a lot of representation-level coupling among classes
     * ForkJoinPool, ForkJoinWorkerThread, and ForkJoinTask.  The
     * fields of WorkQueue maintain data structures managed by
     * ForkJoinPool, so are directly accessed.  There is little point
     * trying to reduce this, since any associated future changes in
     * representations will need to be accompanied by algorithmic
     * changes anyway. Several methods intrinsically sprawl because
     * they must accumulate sets of consistent reads of fields held in
     * local variables. Some others are artificially broken up to
     * reduce producer/consumer imbalances due to dynamic compilation.
     * There are also other coding oddities (including several
     * unnecessary-looking hoisted null checks) that help some methods
     * perform reasonably even when interpreted (not compiled).
     *
     * The order of declarations in this file is (with a few exceptions):
     * (1) Static utility functions
     * (2) Nested (static) classes
     * (3) Static fields
     * (4) Fields, along with constants used when unpacking some of them
     * (5) Internal control methods
     * (6) Callbacks and other support for ForkJoinTask methods
     * (7) Exported methods
     * (8) Static block initializing statics in minimally dependent order
     */

    // Static utilities

    /**
     * If there is a security manager, makes sure caller has
     * permission to modify threads.
     */
    private static void checkPermission() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(modifyThreadPermission);
    }

    // Nested classes

    /**
     * Factory for creating new {@link ForkJoinWorkerThread}s.
     * A {@code ForkJoinWorkerThreadFactory} must be defined and used
     * for {@code ForkJoinWorkerThread} subclasses that extend base
     * functionality or initialize threads with different contexts.
     */
    public static interface ForkJoinWorkerThreadFactory {
        /**
         * Returns a new worker thread operating in the given pool.
         * Returning null or throwing an exception may result in tasks
         * never being executed.  If this method throws an exception,
         * it is relayed to the caller of the method (for example
         * {@code execute}) causing attempted thread creation. If this
         * method returns null or throws an exception, it is not
         * retried until the next attempted creation (for example
         * another call to {@code execute}).
         *
         * @param pool the pool this thread works in
         * @return the new worker thread, or {@code null} if the request
         *         to create a thread is rejected
         * @throws NullPointerException if the pool is null
         */
        public ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    static AccessControlContext contextWithPermissions(Permission ... perms) {
        Permissions permissions = new Permissions();
        for (Permission perm : perms)
            permissions.add(perm);
        return new AccessControlContext(
            new ProtectionDomain[] { new ProtectionDomain(null, permissions) });
    }

    /**
     * Default ForkJoinWorkerThreadFactory implementation; creates a
     * new ForkJoinWorkerThread using the system class loader as the
     * thread context class loader.
     */
    private static final class DefaultForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {
        private static final AccessControlContext ACC = contextWithPermissions(
            new RuntimePermission("getClassLoader"),
            new RuntimePermission("setContextClassLoader"));

        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return AccessController.doPrivileged(
                new PrivilegedAction<>() {
                    public ForkJoinWorkerThread run() {
                        return new ForkJoinWorkerThread(
                            pool, ClassLoader.getSystemClassLoader()); }},
                ACC);
        }
    }

    // Constants shared across ForkJoinPool and WorkQueue

    // Bounds
    static final int SWIDTH       = 16;            // width of short
    static final int SMASK        = 0xffff;        // short bits == max index
    static final int MAX_CAP      = 0x7fff;        // max #workers - 1 即2^15-1
    static final int SQMASK       = 0x007e;        // max 64 (even) slots

    // Masks and units for WorkQueue.phase and ctl sp subfield
    //表示工作线程需要唤醒（工作线程处于阻塞）
    static final int UNSIGNALLED  = 1 << 31;       // must be negative
    //避免ABA问题的版本号递增基数
    static final int SS_SEQ       = 1 << 16;       // version count
    static final int QLOCK        = 1;             // must be 1

    // Mode bits and sentinels, some also used in WorkQueue id and.source fields
    static final int OWNED        = 1;             // queue has owner thread
    static final int FIFO         = 1 << 16;       // fifo queue or access mode
    static final int SHUTDOWN     = 1 << 18;
    static final int TERMINATED   = 1 << 19;
    static final int STOP         = 1 << 31;       // must be negative
    //工作队列静止状态（意味着任务队列中没有任务要执行，也退出了等待队列？？还是任务队列绑定的线程已经终止？？）
    static final int QUIET        = 1 << 30;       // not scanning or working
    static final int DORMANT      = QUIET | UNSIGNALLED;

    /**
     * Initial capacity of work-stealing queue array.
     * Must be a power of two, at least 2.
     */
    static final int INITIAL_QUEUE_CAPACITY = 1 << 13;

    /**
     * Maximum capacity for queue arrays. Must be a power of two less
     * than or equal to 1 << (31 - width of array entry) to ensure
     * lack of wraparound of index calculations, but defined to a
     * value a bit less than this to help users trap runaway programs
     * before saturating systems.
     */
    static final int MAXIMUM_QUEUE_CAPACITY = 1 << 26; // 64M

    /**
     * The maximum number of top-level polls per worker before
     * checking other queues, expressed as a bit shift.  See above for
     * rationale.
     */
    static final int TOP_BOUND_SHIFT = 10;

    /**
     * Queues supporting work-stealing as well as external task
     * submission. See above for descriptions and algorithms.
     */
    @jdk.internal.vm.annotation.Contended
    static final class WorkQueue {
        /**
         * 注意：
         *  虽然各个字段的31位和32位都用同一个常量来表示，或QUIET或UNSIGNALLED，实际上在不同字段中它们含义不尽相同
         *
         * source：
         *      （一般表示有任务正在执行scan操作）任务一般是偷一个执行一个，并不需要
         *      转移队列（不需要入队出队，也就是不需要一直刷新source）。
         *      低16位：表示当前正在偷取的目标任务队列在线程池中的索引。
         *      第31位：标识任务队列的QUITE状态（当前任务队列不为空，但是array中没有任务）
         *      第32位：标识等待被唤醒状态：如果该任务队列等待被唤醒（闲置），则source为负数
         *
         *      source等于0：source为0是一个中间状态，表明线程已经是唤醒状态，但是目前没有在偷任务（大概表示线程正在scan）
         *
         *      source的一个使用：
         *      注意到在ForkJoinPool中，
         *      1）只有runWorker方法会压入任务队列到阻塞队列
         *          在scan搞定所有的任务队列之后，当前任务队列已经确保没有待执行任务了，故将其加入阻塞队列。
         *          不仅只有才有阻塞队列入队操作，也只有这里会设置phase为等待唤醒（unsignalled）。也只有这里有可能
         *          设置phase为quiet（当任务队列从阻塞队列弹出，且它的驻留线程退出时）
         *      2）也只有runWorker方法会执行LockSupport.park操作。
         *          在调用interrupted方法清空中断标志之后，真正阻塞一个线程之前，
         *          会设置对应任务队列的source为quiet和unsignaled。且只有这个地方会设置source的unsignalled位为1.
         *          另外，在ForkJoinPool中，真正的唤醒操作仅在tryCompensate和signalWork两个方法中存在。且在unpark之前
         *          必须满足source为负数（unsignalled标志位等于1）
         *
         * id: workQueue的id字段低16位记录队列在池中的索引（除了共享队列外也会记录工作队列的信息），
         *     高16位包含FIFO信息、OWNED信息（奇偶性表示）、QUIET信息、UNSIGNALLED信息。
         *     但是每次记录只会发生在workQueue新增的时候。这个字段不是volatile的（因为不需要更新？）
         *     只有phase会根据运行时状态发生更新，id不会发生更新（目前看来是这样的。。可能有误）
         *
         * phase：
         *  1： 共享队列上锁。非0和1的时候（值为负数说明在ctl对应的阻塞队列（Treiber stack），
         *      值为正数，说明已经唤醒，不在阻塞队列中）说明该队列是工作队列。
         *      如果是0或者1说明该队列是共享队列，具体细节在id中保存。
         *  低16位：队列在池中的索引
         *  第17位：队列FIFO标志位
         *  第31位：队列静止标志位（表示array中没有任务，且驻留线程已经退出）
         *  第32位：队列唤醒标志位（如果为1，则整体值是负数，任务队列已经加入阻塞队列栈）
         *
         *
         */
        volatile int source;       // source queue id, or sentinel
        int id;                    // pool index, mode, tag
        int base;                  // index of next slot for poll
        int top;                   // index of next slot for push
        volatile int phase;        // versioned, negative: queued, 1: locked
        int stackPred;             // pool stack (ctl) predecessor link
        int nsteals;               // number of steals
        ForkJoinTask<?>[] array;   // the queued tasks; power of 2 size
        final ForkJoinPool pool;   // the containing pool (may be null)
        final ForkJoinWorkerThread owner; // owning thread or null if shared

        WorkQueue(ForkJoinPool pool, ForkJoinWorkerThread owner) {
            this.pool = pool;
            this.owner = owner;
            // Place indices in the center of array (that is not yet allocated)
            base = top = INITIAL_QUEUE_CAPACITY >>> 1;
        }

        /**
         * Tries to lock shared queue by CASing phase field.
         */
        final boolean tryLockPhase() {
            return PHASE.compareAndSet(this, 0, 1);
        }

        final void releasePhaseLock() {
            PHASE.setRelease(this, 0);
        }

        /**
         * Returns an exportable index (used by ForkJoinWorkerThread).
         */
        final int getPoolIndex() {
            return (id & 0xffff) >>> 1; // ignore odd/even tag bit
        }

        /**
         * Returns the approximate number of tasks in the queue.
         */
        final int queueSize() {
            int n = (int)BASE.getAcquire(this) - top;
            return (n >= 0) ? 0 : -n; // ignore transient negative
        }

        /**
         * Provides a more accurate estimate of whether this queue has
         * any tasks than does queueSize, by checking whether a
         * near-empty queue has at least one unclaimed task.
         */
        final boolean isEmpty() {
            ForkJoinTask<?>[] a; int n, cap, b;
            VarHandle.acquireFence(); // needed by external callers
            return ((n = (b = base) - top) >= 0 || // possibly one task
                    (n == -1 && ((a = array) == null ||
                                 (cap = a.length) == 0 ||
                                 a[(cap - 1) & b] == null)));
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.
         *
         * @param task the task. Caller must ensure non-null.
         * @throws RejectedExecutionException if array cannot be resized
         */
        final void push(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a;
            int s = top, d = s - base, cap, m;
            ForkJoinPool p = pool;
            if ((a = array) != null && (cap = a.length) > 0) {
                QA.setRelease(a, (m = cap - 1) & s, task);
                top = s + 1;
                if (d == m)
                    growArray(false);
                else if (QA.getAcquire(a, m & (s - 1)) == null && p != null) {
                    VarHandle.fullFence();  // was empty
                    p.signalWork(null);
                }
            }
        }

        /**
         * Version of push for shared queues. Call only with phase lock held.
         * @return true if should signal work
         */
        final boolean lockedPush(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a;
            boolean signal = false;
            int s = top, d = s - base, cap, m;
            if ((a = array) != null && (cap = a.length) > 0) {
                a[(m = (cap - 1)) & s] = task;
                top = s + 1;
                if (d == m)
                    growArray(true);
                else {
                    phase = 0; // full volatile unlock
                    //任务太少，唤醒线程来生产任务
                    if (((s - base) & ~1) == 0) // size 0 or 1
                        signal = true;
                }
            }
            return signal;
        }

        /**
         * Doubles the capacity of array. Call either by owner or with
         * lock held -- it is OK for base, but not top, to move while
         * resizings are in progress.
         */
        final void growArray(boolean locked) {
            ForkJoinTask<?>[] newA = null;
            try {
                ForkJoinTask<?>[] oldA; int oldSize, newSize;
                if ((oldA = array) != null && (oldSize = oldA.length) > 0 &&
                    (newSize = oldSize << 1) <= MAXIMUM_QUEUE_CAPACITY &&
                    newSize > 0) {
                    try {
                        newA = new ForkJoinTask<?>[newSize];
                    } catch (OutOfMemoryError ex) {
                    }
                    if (newA != null) { // poll from old array, push to new
                        int oldMask = oldSize - 1, newMask = newSize - 1;
                        for (int s = top - 1, k = oldMask; k >= 0; --k) {
                            ForkJoinTask<?> x = (ForkJoinTask<?>)
                                QA.getAndSet(oldA, s & oldMask, null);
                            if (x != null)
                                newA[s-- & newMask] = x;
                            else
                                break;
                        }
                        array = newA;
                        VarHandle.releaseFence();
                    }
                }
            } finally {
                if (locked)
                    phase = 0;
            }
            if (newA == null)
                throw new RejectedExecutionException("Queue capacity exceeded");
        }

        /**
         * Takes next task, if one exists, in FIFO order.
         */
        final ForkJoinTask<?> poll() {
            int b, k, cap; ForkJoinTask<?>[] a;
            while ((a = array) != null && (cap = a.length) > 0 &&
                   top - (b = base) > 0) {
                ForkJoinTask<?> t = (ForkJoinTask<?>)
                    QA.getAcquire(a, k = (cap - 1) & b);
                if (base == b++) {
                    if (t == null)
                        Thread.yield(); // await index advance
                    else if (QA.compareAndSet(a, k, t, null)) {
                        BASE.setOpaque(this, b);
                        return t;
                    }
                }
            }
            return null;
        }

        /**
         * Takes next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> nextLocalTask() {
            ForkJoinTask<?> t = null;
            int md = id, b, s, d, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0 &&
                (d = (s = top) - (b = base)) > 0) {
                if ((md & FIFO) == 0 || d == 1) {
                    if ((t = (ForkJoinTask<?>)
                         QA.getAndSet(a, (cap - 1) & --s, null)) != null)
                        TOP.setOpaque(this, s);
                }
                else if ((t = (ForkJoinTask<?>)
                          QA.getAndSet(a, (cap - 1) & b++, null)) != null) {
                    BASE.setOpaque(this, b);
                }
                else // on contention in FIFO mode, use regular poll
                    t = poll();
            }
            return t;
        }

        /**
         * Returns next task, if one exists, in order specified by mode.
         */
        final ForkJoinTask<?> peek() {
            int cap; ForkJoinTask<?>[] a;
            return ((a = array) != null && (cap = a.length) > 0) ?
                a[(cap - 1) & ((id & FIFO) != 0 ? base : top - 1)] : null;
        }

        /**
         * Pops the given task only if it is at the current top.
         */
        final boolean tryUnpush(ForkJoinTask<?> task) {
            boolean popped = false;
            int s, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0 &&
                (s = top) != base &&
                (popped = QA.compareAndSet(a, (cap - 1) & --s, task, null)))
                TOP.setOpaque(this, s);
            return popped;
        }

        /**
         * Shared version of tryUnpush.
         */
        final boolean tryLockedUnpush(ForkJoinTask<?> task) {
            boolean popped = false;
            int s = top - 1, k, cap; ForkJoinTask<?>[] a;
            if ((a = array) != null && (cap = a.length) > 0 &&
                a[k = (cap - 1) & s] == task && tryLockPhase()) {
                if (top == s + 1 && array == a &&
                    (popped = QA.compareAndSet(a, k, task, null)))
                    top = s;
                releasePhaseLock();
            }
            return popped;
        }

        /**
         * Removes and cancels all known tasks, ignoring any exceptions.
         */
        final void cancelAll() {
            for (ForkJoinTask<?> t; (t = poll()) != null; )
                ForkJoinTask.cancelIgnoringExceptions(t);
        }

        // Specialized execution methods

        /**
         * Runs the given (stolen) task if nonnull, as well as
         * remaining local tasks and others available from the given
         * queue, up to bound n (to avoid infinite unfairness).
         */
        final void topLevelExec(ForkJoinTask<?> t, WorkQueue q, int n) {
            //初始化被偷数量为1
            int nstolen = 1;
            for (int j = 0;;) {
                if (t != null)
                    //执行任务，初始值是参数中传入的被偷到的任务
                    t.doExec();
                if (j++ <= n)
                    //获取当前任务队列中其它的任务
                    t = nextLocalTask();
                else {
                    j = 0;
                    t = null;
                }
                if (t == null) {
                    //从本次（外层循环指定）任务队列头部（base）获取其它的任务，每重新偷一个，被偷数量nstolen加1.
                    if (q != null && (t = q.poll()) != null) {
                        ++nstolen;
                        j = 0;
                    }
                    else if (j != 0)
                        break;
                }
            }
            ForkJoinWorkerThread thread = owner;
            //当前队列已偷（并处理）任务数量维护
            nsteals += nstolen;
            //这里设置为0，是因为已经没有任务要执行了，但是线程又没有退出，在方法退出后最后在runWorker中将任务队列压入阻塞队列之后
            //会将source置为负数（所以source为0是一个中间状态，已经是唤醒状态，但是目前没有在偷任务）
            source = 0;
            if (thread != null)
                //工作者线程执行完上面的任务后，会触发一次 afterTopLevelExec 回调
                thread.afterTopLevelExec();
        }

        /**
         * If present, removes task from queue and executes it.
         */
        final void tryRemoveAndExec(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; int s, cap;
            if ((a = array) != null && (cap = a.length) > 0 &&
                (s = top) - base > 0) { // traverse from top
                for (int m = cap - 1, ns = s - 1, i = ns; ; --i) {
                    int index = i & m;
                    ForkJoinTask<?> t = (ForkJoinTask<?>)QA.get(a, index);
                    if (t == null)
                        break;
                    else if (t == task) {
                        if (QA.compareAndSet(a, index, t, null)) {
                            top = ns;   // safely shift down
                            //安全地将任务队列中的任务下移，以填充移除造成的空值
                            for (int j = i; j != ns; ++j) {
                                ForkJoinTask<?> f;
                                int pindex = (j + 1) & m;
                                f = (ForkJoinTask<?>)QA.get(a, pindex);
                                QA.setVolatile(a, pindex, null);
                                int jindex = j & m;
                                QA.setRelease(a, jindex, f);
                            }
                            VarHandle.releaseFence();
                            t.doExec();
                        }
                        break;
                    }
                }
            }
        }

        /**
         * Tries to pop and run tasks within the target's computation
         * until done, not found, or limit exceeded.
         *
         * @param task root of CountedCompleter computation
         * @param limit max runs, or zero for no limit 最大循环次数，如果为0则表示没有上限
         * @param shared true if must lock to extract task
         * @return task status on exit
         */
        final int helpCC(CountedCompleter<?> task, int limit, boolean shared) {
            int status = 0;
            if (task != null && (status = task.status) >= 0) {
                int s, k, cap; ForkJoinTask<?>[] a;
                //只要对应任务队列中还有任务，就继续循环
                while ((a = array) != null && (cap = a.length) > 0 &&
                       (s = top) - base > 0) {
                    CountedCompleter<?> v = null;
                    //取出任务队列顶部任务
                    ForkJoinTask<?> o = a[k = (cap - 1) & (s - 1)];
                    if (o instanceof CountedCompleter) {
                        CountedCompleter<?> t = (CountedCompleter<?>)o;
                        for (CountedCompleter<?> f = t;;) {
                            if (f != task) {
                                if ((f = f.completer) == null)
                                    break;
                            }
                            else if (shared) {
                                if (tryLockPhase()) {
                                    if (top == s && array == a &&
                                        QA.compareAndSet(a, k, t, null)) {
                                        top = s - 1;
                                        v = t;
                                    }
                                    releasePhaseLock();
                                }
                                break;
                            }
                            else {
                                if (QA.compareAndSet(a, k, t, null)) {
                                    top = s - 1;
                                    v = t;
                                }
                                break;
                            }
                        }
                    }
                    if (v != null)
                        v.doExec();
                    if ((status = task.status) < 0 || v == null ||
                        (limit != 0 && --limit == 0))
                        break;
                }
            }
            return status;
        }

        /**
         * Tries to poll and run AsynchronousCompletionTasks until
         * none found or blocker is released
         *
         * @param blocker the blocker
         */
        final void helpAsyncBlocker(ManagedBlocker blocker) {
            if (blocker != null) {
                int b, k, cap; ForkJoinTask<?>[] a; ForkJoinTask<?> t;
                while ((a = array) != null && (cap = a.length) > 0 &&
                       top - (b = base) > 0) {
                    t = (ForkJoinTask<?>)QA.getAcquire(a, k = (cap - 1) & b);
                    if (blocker.isReleasable())
                        break;
                    else if (base == b++ && t != null) {
                        if (!(t instanceof CompletableFuture.
                              AsynchronousCompletionTask))
                            break;
                        else if (QA.compareAndSet(a, k, t, null)) {
                            BASE.setOpaque(this, b);
                            t.doExec();
                        }
                    }
                }
            }
        }

        /**
         * Returns true if owned and not known to be blocked.
         */
        final boolean isApparentlyUnblocked() {
            Thread wt; Thread.State s;
            return ((wt = owner) != null &&
                    (s = wt.getState()) != Thread.State.BLOCKED &&
                    s != Thread.State.WAITING &&
                    s != Thread.State.TIMED_WAITING);
        }

        // VarHandle mechanics.
        static final VarHandle PHASE;
        static final VarHandle BASE;
        static final VarHandle TOP;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                PHASE = l.findVarHandle(WorkQueue.class, "phase", int.class);
                BASE = l.findVarHandle(WorkQueue.class, "base", int.class);
                TOP = l.findVarHandle(WorkQueue.class, "top", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    // static fields (initialized in static initializer below)

    /**
     * Creates a new ForkJoinWorkerThread. This factory is used unless
     * overridden in ForkJoinPool constructors.
     */
    public static final ForkJoinWorkerThreadFactory
        defaultForkJoinWorkerThreadFactory;

    /**
     * Permission required for callers of methods that may start or
     * kill threads.
     */
    static final RuntimePermission modifyThreadPermission;

    /**
     * Common (static) pool. Non-null for public use unless a static
     * construction exception, but internal usages null-check on use
     * to paranoically avoid potential initialization circularities
     * as well as to simplify generated code.
     */
    static final ForkJoinPool common;

    /**
     * Common pool parallelism. To allow simpler use and management
     * when common pool threads are disabled, we allow the underlying
     * common.parallelism field to be zero, but in that case still report
     * parallelism as 1 to reflect resulting caller-runs mechanics.
     */
    static final int COMMON_PARALLELISM;

    /**
     * Limit on spare thread construction in tryCompensate.
     */
    private static final int COMMON_MAX_SPARES;

    /**
     * Sequence number for creating workerNamePrefix.
     */
    private static int poolNumberSequence;

    /**
     * Returns the next sequence number. We don't expect this to
     * ever contend, so use simple builtin sync.
     */
    private static final synchronized int nextPoolId() {
        return ++poolNumberSequence;
    }

    // static configuration constants

    /**
     * Default idle timeout value (in milliseconds) for the thread
     * triggering quiescence to park waiting for new work
     */
    private static final long DEFAULT_KEEPALIVE = 60_000L;

    /**
     * Undershoot tolerance for idle timeouts 允许的误差
     */
    private static final long TIMEOUT_SLOP = 20L;

    /**
     * The default value for COMMON_MAX_SPARES.  Overridable using the
     * "java.util.concurrent.ForkJoinPool.common.maximumSpares" system
     * property.  The default value is far in excess of normal
     * requirements, but also far short of MAX_CAP and typical OS
     * thread limits, so allows JVMs to catch misuse/abuse before
     * running out of resources needed to do so.
     */
    private static final int DEFAULT_COMMON_MAX_SPARES = 256;

    /**
     * Increment for seed generators. See class ThreadLocal for
     * explanation.
     * 这个数来自黄金分割率：0x9e3779b9/0x100000000 = 2654435769/4294967296 ≈ 0.6180339886
     * 用它除以2^32即黄金分割率。相当于将2^32比特黄金分割的一个位置。
     */
    private static final int SEED_INCREMENT = 0x9e3779b9;

    /*
     * Bits and masks for field ctl, packed with 4 16 bit subfields:
     * RC: Number of released (unqueued) workers minus target parallelism
     * TC: Number of total workers minus target parallelism
     * SS: version count and status of top waiting thread
     * ID: poolIndex of top of Treiber stack of waiters
     *
     * When convenient, we can extract the lower 32 stack top bits
     * (including version bits) as sp=(int)ctl.  The offsets of counts
     * by the target parallelism and the positionings of fields makes
     * it possible to perform the most common checks via sign tests of
     * fields: When ac is negative, there are not enough unqueued
     * workers, when tc is negative, there are not enough total
     * workers.  When sp is non-zero, there are waiting workers.  To
     * deal with possibly negative fields, we use casts in and out of
     * "short" and/or signed shifts to maintain signedness.
     *
     * Because it occupies uppermost bits, we can add one release count
     * using getAndAddLong of RC_UNIT, rather than CAS, when returning
     * from a blocked join.  Other updates entail multiple subfields
     * and masking, requiring CAS.
     *
     * The limits packed in field "bounds" are also offset by the
     * parallelism level to make them comparable to the ctl rc and tc
     * fields.
     */
    /*
     * ctl在forljoinpool的构造函数中初始化，
     * TC：初始化为corePoolSize池中常驻线程值得补码，即实际线程数大于等于
     *     预设常驻线程数，该值才会大于等于0.
     * RC：初始化为并行系数parallelism的补码，即实际活跃线程数大于等于并行系数
     *     该值才会大于等于0
     * SS：等待线程栈，栈顶线程的版本号及线程状态
     * ID：等待线程栈，栈顶线程在forkJoinPool池中的索引（workQueues索引）
     *
     * 当rc为负数，说明现在存在的活跃线程数还没到目标值（小于预计的并发系数）
     * 当tc为负数，说明现在已创建的线程数还没达到目标值（小于预计的核心池大小）
     * 我们使 sp = （int)ctl;
     * 当sp值非0：说明当前等待区线程栈非空，有线程正在等待中。
     */

    // Lower and upper word masks
    private static final long SP_MASK    = 0xffffffffL;
    private static final long UC_MASK    = ~SP_MASK;

    // Release counts
    private static final int  RC_SHIFT   = 48;
    private static final long RC_UNIT    = 0x0001L << RC_SHIFT;
    private static final long RC_MASK    = 0xffffL << RC_SHIFT;

    // Total counts
    private static final int  TC_SHIFT   = 32;
    private static final long TC_UNIT    = 0x0001L << TC_SHIFT;
    private static final long TC_MASK    = 0xffffL << TC_SHIFT;
    private static final long ADD_WORKER = 0x0001L << (TC_SHIFT + 15); // sign

    // Instance fields

    /*
     * stealCount：
     * ForkJoinPool池中通过偷窃完成的任务总数的一个估计值。该值保持很高说明线程很忙，该值变低则表示
     * 负载和竞争在减少。
     *
     * bounds ：
     * 低16位表示除开并行系数外至少应该有的线程数（为了满足最小可用限制）
     * 高16位表示除开并行系数外最大可创建线程数
     * 根据线程创建销毁应该动态维护
     *
     * mode：
     * 初始化后，低16位是并行系数，高16位（第17位）标识asyncMode，第19位标识SHUTDOWN，第20位标识TERMINATED，第32位标识STOP（负数）
     *
     * saturate：
     * 当线程将要在join或者ForkJoinPool.ManagedBlocker阻塞时，如果由于将达到maximumPoolSize
     * 而无法发生替换，将引发RejectedExecutionException异常。但是如果该谓词非空，则会先对当前
     * ForkJoinPool调用该谓词，如果返回true，则不会抛出异常而是以少于目标可运行数量的线程数运行
     * 这可能无法保证进度。
     */
    volatile long stealCount;            // collects worker nsteals
    final long keepAlive;                // milliseconds before dropping if idle
    int indexSeed;                       // next worker index
    final int bounds;                    // min, max threads packed as shorts
    volatile int mode;                   // parallelism, runstate, queue mode
    WorkQueue[] workQueues;              // main registry
    final String workerNamePrefix;       // for worker thread string; sync lock
    final ForkJoinWorkerThreadFactory factory;
    final UncaughtExceptionHandler ueh;  // per-worker UEH
    final Predicate<? super ForkJoinPool> saturate;

    @jdk.internal.vm.annotation.Contended("fjpctl") // segregate
    volatile long ctl;                   // main pool control

    // Creating, registering and deregistering workers

    /**
     * Tries to construct and start one worker. Assumes that total
     * count has already been incremented as a reservation.  Invokes
     * deregisterWorker on any failure.
     * 创建一个线程，并新绑定一个新增的任务队列，还会在任务池中找个空槽装入这个任务队列
     *
     * @return true if successful
     */
    private boolean createWorker() {
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
            if (fac != null && (wt = fac.newThread(this)) != null) {
                wt.start();
                return true;
            }
        } catch (Throwable rex) {
            ex = rex;
        }
        deregisterWorker(wt, ex);
        return false;
    }

    /**
     * Tries to add one worker, incrementing ctl counts before doing
     * so, relying on createWorker to back out on failure.
     *
     * @param c incoming ctl value, with total count negative and no
     * idle workers.  On CAS failure, c is refreshed and retried if
     * this holds (otherwise, a new worker is not needed).
     */
    private void tryAddWorker(long c) {
        do {
            long nc = ((RC_MASK & (c + RC_UNIT)) |
                       (TC_MASK & (c + TC_UNIT)));
            if (ctl == c && CTL.compareAndSet(this, c, nc)) {
                createWorker();
                break;
            }
        } while (((c = ctl) & ADD_WORKER) != 0L && (int)c == 0);
    }

    /**
     * Callback from ForkJoinWorkerThread constructor to establish and
     * record its WorkQueue.
     *
     * @param wt the worker thread
     * @return the worker's queue
     */
    final WorkQueue registerWorker(ForkJoinWorkerThread wt) {
        UncaughtExceptionHandler handler;
        //将传入的线程设置为守护线程
        wt.setDaemon(true);                             // configure thread
        if ((handler = ueh) != null)
            wt.setUncaughtExceptionHandler(handler);
        int tid = 0;                                    // for thread name
        //从池中获取asyncMode，除此位（第17位）之外，别的位都是0
        int idbits = mode & FIFO;
        String prefix = workerNamePrefix;
        WorkQueue w = new WorkQueue(this, wt);
        if (prefix != null) {
            synchronized (prefix) {
                WorkQueue[] ws = workQueues; int n;
                //根据原来的种子索引算得一个新的32位的种子索引
                int s = indexSeed += SEED_INCREMENT;
                /*
                 * 计算种子索引（通过重置其中的各个标志位）,并与原来的idbits（只有第17位有意义）或操作得到idbits。某些特定位具备特殊含义
                 * 满足：（FIFO标志继承池mode属性）
                 * 1) 低16位为0，
                 * 2) 第17位为0：后进先出
                 * 3）第31位为0：非静止
                 * 4）第32位为0：不需要等待唤醒
                 * ~(SMASK | FIFO | DORMANT)高两位为0，低17位为0
                 *
                 * 可知最终idbits，高两位必为0，低16位必为0
                 */
                idbits |= (s & ~(SMASK | FIFO | DORMANT));
                if (ws != null && (n = ws.length) > 1) {
                    int m = n - 1;
                    //将种子索引处理为奇数，并安全取模（worker队列位于队列数组奇数索引处）。tid中的任何位都不具备特殊含义
                    tid = m & ((s << 1) | 1);           // odd-numbered indices
                    //遍历所有的奇数槽，寻找一个空槽。tid从随机奇数槽开始，每次循环改变
                    for (int probes = n >>> 1;;) {      // find empty slot
                        WorkQueue q;
                        //如果当前tid对应的槽为空，或者槽中队列为静止状态，则找到并退出
                        if ((q = ws[tid]) == null || q.phase == QUIET)
                            break;
                        //遍历完所有奇数槽没找到满足条件的队列，tid设置为队列数组大小（偶数）加1.并会在后面马上扩容队列数组
                        else if (--probes == 0) {
                            tid = n | 1;                // resize below
                            break;
                        }
                        //不满足上面两个结束条件，在一个奇数队列中（1，3，5，7..，m)从tid开始移动tid，以便遍历所有奇数槽
                        else
                            tid = (tid + 2) & m;
                    }
                    /*
                     * 已知tid必然小于2^30，（按道理来说tid应该是小于2^16的，这样下面的计算才有意义，
                     * 因为idbits保证腾空的地方也是低16位）
                     * 实际forkJoinPool初始化的时候限制了工作线程最大数量为2^15-1个，即MAX_CAP，所以tid按理也应该小于这个数
                     * 当然，目前还没有找到明确的限制这个值的地方，后续队列数组扩容也没有找到限制大小的地方。
                     *
                     * 注意到，workQueue的id字段除了共享队列外也会记录工作队列的信息，但是每次记录只会发生在workQueue新增的时候
                     * 只有phase会根据运行时状态发生更新，id不会发生更新（目前看来是这样的。。可能有误）
                     */
                    w.phase = w.id = tid | idbits;      // now publishable

                    if (tid < n)
                        ws[tid] = w;
                    //计算出的tid不在原队列数组，则数组扩容
                    else {                              // expand array
                        //n最大值2^30,(2^31已经是负数了，n又必须是偶数)如果n已经是2^30则，an为负数，初始化数组会抛出异常
                        //正常分析是会抛异常的，不知道是否漏掉啥细节了
                        int an = n << 1;
                        WorkQueue[] as = new WorkQueue[an];
                        //此时原队列数组的对应槽本来就为空，不用担心被后续循环覆盖
                        as[tid] = w;
                        int am = an - 1;
                        //队列数组扩容，这里可以看出，偶数槽（共享队列）最多64个，奇数槽（任务队列）没有限制
                        for (int j = 0; j < n; ++j) {
                            WorkQueue v;                // copy external queue
                            if ((v = ws[j]) != null)    // position may change
                                as[v.id & am & SQMASK] = v;
                            if (++j >= n)
                                break;
                            as[j] = ws[j];              // copy worker
                        }
                        workQueues = as;
                    }
                }
            }
            wt.setName(prefix.concat(Integer.toString(tid)));
        }
        return w;
    }

    /**
     * Final callback from terminating worker, as well as upon failure
     * to construct or start a worker.  Removes record of worker from
     * array, and adjusts counts. If pool is shutting down, tries to
     * complete termination.
     *
     * @param wt the worker thread, or null if construction failed
     * @param ex the exception causing failure, or null if none
     */
    final void deregisterWorker(ForkJoinWorkerThread wt, Throwable ex) {
        WorkQueue w = null;
        int phase = 0;
        if (wt != null && (w = wt.workQueue) != null) {
            Object lock = workerNamePrefix;
            int wid = w.id;
            long ns = (long)w.nsteals & 0xffffffffL;
            if (lock != null) {
                synchronized (lock) {
                    WorkQueue[] ws; int n, i;         // remove index from array
                    if ((ws = workQueues) != null && (n = ws.length) > 0 &&
                        ws[i = wid & (n - 1)] == w)
                        ws[i] = null;
                    //任务队列从队列池中彻底移除之前，保留一下偷窃数
                    stealCount += ns;
                }
            }
            phase = w.phase;
        }
        //如果任务队列中的驻留线程还没有退出
        if (phase != QUIET) {                         // else pre-adjusted
            long c;                                   // decrement counts
            do {} while (!CTL.weakCompareAndSet
                         (this, c = ctl, ((RC_MASK & (c - RC_UNIT)) |
                                          (TC_MASK & (c - TC_UNIT)) |
                                          (SP_MASK & c))));
        }
        if (w != null)
            w.cancelAll();                            // cancel remaining tasks

        if (!tryTerminate(false, false) &&            // possibly replace worker
            w != null && w.array != null)             // avoid repeated failures
            signalWork(null);

        if (ex == null)                               // help clean on way out
            ForkJoinTask.helpExpungeStaleExceptions();
        else                                          // rethrow
            ForkJoinTask.rethrow(ex);
    }

    /**
     * Tries to create or release a worker if too few are running.
     * @param q if non-null recheck if empty on CAS failure
     */
    final void signalWork(WorkQueue q) {
        for (;;) {
            long c; int sp; WorkQueue[] ws; int i; WorkQueue v;
            if ((c = ctl) >= 0L)                      // enough workers
                break;
            else if ((sp = (int)c) == 0) {            // no idle workers 没有正在闲置的任务
                if ((c & ADD_WORKER) != 0L)           // too few workers TC为负数，说明总工作线程数量过少，需要创建
                    tryAddWorker(c);
                break;
            }
            else if ((ws = workQueues) == null)       //任务队列数组为空，说明线程池未启动或已经终止
                break;                                // unstarted/terminated
            else if (ws.length <= (i = sp & SMASK)) //拿到栈顶工作队列（线程）在池中的索引
                break;                                // terminated
            else if ((v = ws[i]) == null)
                break;                                // terminating
            else {
                //准备唤醒ctl的栈顶线程
                int np = sp & ~UNSIGNALLED;
                int vp = v.phase;
                long nc = (v.stackPred & SP_MASK) | (UC_MASK & (c + RC_UNIT));
                Thread vt = v.owner;
                /*
                 * ctl的sp和ctl中栈顶队列的phase相等，且将ctl CAS更新为弹出栈顶队列后的效果成功
                 * ctl栈顶指向原栈顶队列的前一个队列。即顺位第二最近被使用队列，活跃线程rc计数加一
                 * 总体来说：
                 * 如果当前工作队列是最近静止的或其工作者线程是最近阻塞的，则尝试恢复为静止之前的控制变量（回滚一下）
                 */
                if (sp == vp && CTL.compareAndSet(this, c, nc)) {
                    //弹出原栈顶队列之后要更新原栈顶队列phase值，设置需要唤醒标志为0（此时phase变成了一个正数）
                    v.phase = np;
                    //如果原栈顶队列的驻留线程非空且原栈顶队列偷取的目标队列为未唤醒
                    if (vt != null && v.source < 0)
                        LockSupport.unpark(vt);
                    break;
                }
                else if (q != null && q.isEmpty())     // no need to retry
                    break;
            }
        }
    }

    /**
     * Tries to decrement counts (sometimes implicitly) and possibly
     * arrange for a compensating worker in preparation for blocking:
     * If not all core workers yet exist, creates one, else if any are
     * unreleased (possibly including caller) releases one, else if
     * fewer than the minimum allowed number of workers running,
     * checks to see that they are all active, and if so creates an
     * extra worker unless over maximum limit and policy is to
     * saturate.  Most of these steps can fail due to interference, in
     * which case 0 is returned so caller will retry. A negative
     * return value indicates that the caller doesn't need to
     * re-adjust counts when later unblocked.
     *
     *
     * 这里的counts都是在说RC
     * 因为这里释放一个线程，但是外面的任务队列线程最终会阻塞。如果外面的线程（调用tryCompensate方法的线程）原来就是阻塞状态，
     * 那么RC加1没有问题。如果外面的线程原来不是阻塞状态，那么直到这个方法退出，RC都不应该加1.因为这个方法退出之后第一件事情就是
     * 阻塞外面的线程（最终对冲调方法内部新增或释放的线程）。不过，要注意的是，如果外部线程阻塞结束之后，要将RC加1，意义就是把本方法
     * 新增或释放的那个线程的计数补上（对冲结束）。
     * 返回1：说明返回后要阻塞且调整
     * 返回-1：说明返回后要阻塞
     * 返回0: 说明需要重试
     * @return 1: block then adjust, -1: block without adjust, 0 : retry
     */
    private int tryCompensate(WorkQueue w) {
        int t, n, sp;
        long c = ctl;
        WorkQueue[] ws = workQueues;
        //总常驻线程数量足够
        if ((t = (short)(c >>> TC_SHIFT)) >= 0) {
            if (ws == null || (n = ws.length) <= 0 || w == null)
                return 0;                        // disabled
            //阻塞队列有任务
            else if ((sp = (int)c) != 0) {       // replace or release
                WorkQueue v = ws[sp & (n - 1)];
                int wp = w.phase;
                //如果给定任务队列之前在栈内：则RC计数加1 （tax‘mark）
                //否则RC计数不变
                long uc = UC_MASK & ((wp < 0) ? c + RC_UNIT : c);
                int np = sp & ~UNSIGNALLED;
                if (v != null) {
                    int vp = v.phase;
                    Thread vt = v.owner;
                    long nc = ((long)v.stackPred & SP_MASK) | uc;
                    //弹出阻塞队列栈顶任务队列
                    if (vp == sp && CTL.compareAndSet(this, c, nc)) {
                        //修改为栈外状态
                        v.phase = np;
                        //如果弹出的任务队列的驻留线程在阻塞等唤醒， 就唤醒它
                        if (vt != null && v.source < 0)
                            LockSupport.unpark(vt);
                        //阻塞队列弹出操作后，检查一下给定任务队列。
                        // 如果之前在栈内返回-1：该方法返回阻塞结束之后不需要重新修改RC（在tax‘mark中已经加过一次了）
                        // 如果之前在栈外返回1：该方法返回之后，且阻塞结束之后还需要重新修改RC，需要在外层把欠加的RC加回来
                        return (wp < 0) ? -1 : 1;
                    }
                }
                //如果阻塞队列没改变，返回0
                return 0;
            }
            //如果阻塞队列没任务（说明外部线程身是活跃的），且活跃线程过剩，那么减一下ctl对应数值，因为方法返回后外部线程阻塞
            //如果修改ctl失败，需要在外部大循环中重试
            else if ((int)(c >> RC_SHIFT) -      // reduce parallelism
                     (short)(bounds & SMASK) > 0) {
                long nc = ((RC_MASK & (c - RC_UNIT)) | (~RC_MASK & c));
                return CTL.compareAndSet(this, c, nc) ? 1 : 0;
            }
            //任务队列池中有任务没完成，但是阻塞队列中没有线程阻塞，而且活跃线程暂时不够
            else {                               // validate
                int md = mode, pc = md & SMASK, tc = pc + t, bc = 0;
                boolean unstable = false;
                //遍历所有的工作队列
                for (int i = 1; i < n; i += 2) {
                    WorkQueue q; Thread wt; Thread.State ts;
                    if ((q = ws[i]) != null) {
                        if (q.source == 0) {
                            //有任务在scan中，可能正在消耗任务队列池中的任务，于是没有理由再释放新的线程来做活跃线程
                            unstable = true;
                            break;//这里相当于return 0；
                        }
                        else {
                            //如果遍历一遍tc不等于0，说明线程够用
                            --tc;
                            if ((wt = q.owner) != null &&
                                ((ts = wt.getState()) == Thread.State.BLOCKED ||
                                 ts == Thread.State.WAITING))
                                //正在阻塞的工作线程数量
                                ++bc;            // worker is blocking
                        }
                    }
                }
                //ctl！=c说明阻塞队列已经被别的线程释放过
                if (unstable || tc != 0 || ctl != c)
                    return 0;                    // inconsistent
                else if (t + pc >= MAX_CAP || t >= (bounds >>> SWIDTH)) {
                    //常驻线程过多
                    Predicate<? super ForkJoinPool> sat;
                    //根据拒接策略判断是否在该方法中放行线程
                    if ((sat = saturate) != null && sat.test(this))
                        return -1;
                    else if (bc < pc) {          // lagging
                        //常驻线程过多，活跃线程不够，阻塞中的工作线程小于并行系数。下面就让出cpu，给那些阻塞线程机会执行
                        Thread.yield();          // for retry spins
                        return 0;
                    }
                    else
                        //如果阻塞的线程太多了，直接拒接本线程
                        throw new RejectedExecutionException(
                            "Thread limit exceeded replacing blocked worker");
                }
            }
        }
        //创建新线程
        long nc = ((c + TC_UNIT) & TC_MASK) | (c & ~TC_MASK); // expand pool
        return CTL.compareAndSet(this, c, nc) && createWorker() ? 1 : 0;
    }

    /**
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     * See above for explanation.
     */
    final void runWorker(WorkQueue w) {
        int r = (w.id ^ ThreadLocalRandom.nextSecondarySeed()) | FIFO; // rng
        w.array = new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY]; // initialize
        for (;;) {
            int phase;
            if (scan(w, r)) {                     // scan until apparently empty
                r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // move (xorshift)
            }
            //>>>>>>>>>>>>>>>>>>>>执行到这一步，说明完成了w的所有任务执行，w线程可以休眠<<<<<<<<<<<<<<<<<<<<<
            //如果给定任务队列为已唤醒状态，将其放入未唤醒队列，并刷新ctl，然后接着扫描
            else if ((phase = w.phase) >= 0) {    // enqueue, then rescan
                //递增phase版本号，设置phase为入栈状态
                long np = (w.phase = (phase + SS_SEQ) | UNSIGNALLED) & SP_MASK;
                long c, nc;
                do {
                    w.stackPred = (int)(c = ctl);
                    //减少一个活跃线程计数
                    nc = ((c - RC_UNIT) & UC_MASK) | np;
                } while (!CTL.weakCompareAndSet(this, c, nc));
            }
            //如果给定任务队列为入栈状态（根据上个if条件，此时w应该已经进入阻塞队列中了）
            else {                                // already queued
                int pred = w.stackPred;
                //清空中断标志，以允许阻塞（如果中断标志为设置状态，遇到阻塞会先清除中断标志再抛出异常）
                Thread.interrupted();             // clear before park
                //这里source不在表示正在偷取的队列索引，而是标志对任务队列w为静止状态可以被唤醒（特别之处是已经清空中断状态吗？）
                //此时任务队列w还在阻塞队列栈顶，且任务队列w中应该没有任务了，（下面的操作是说w偷取目标等待被唤醒？）
                w.source = DORMANT;               // enable signal
                long c = ctl;
                //rc=并行系数+ctl中的RC值
                int md = mode, rc = (md & SMASK) + (int)(c >> RC_SHIFT);
                //如果池状态为STOP即正在停止，直接退出
                if (md < 0)                       // terminating
                    break;
                //如果活跃线程数量没有超过预设并发系数，且池正在关闭，且关闭成功，停止成功。之后退出
                else if (rc <= 0 && (md & SHUTDOWN) != 0 &&
                         tryTerminate(false, false))
                    break;                        // quiescent shutdown
                //如果给定任务队列还在入栈状态
                else if (w.phase < 0) {
                    //如果活跃线程数少于预设阈值，且阻塞队列栈中还有别的线程，且给定任务为栈顶任务，
                    // 则先阻塞给定任务队列一个keepAlive的随时间
                    if (rc <= 0 && pred != 0 && phase == (int)c) {
                        //减少一个常驻线程，w退出阻塞队列栈顶
                        long nc = (UC_MASK & (c - TC_UNIT)) | (SP_MASK & pred);
                        long d = keepAlive + System.currentTimeMillis();
                        LockSupport.parkUntil(this, d);
                        //如果在指定时间后，给定的任务没有被别的线程唤醒，则回滚等待栈，指向上一次入栈的等待线程，
                        // 并将给定任务队列设置为静止状态（意味着任务队列中没有任务要执行，也退出了等待队列）
                        if (ctl == c &&           // drop on timeout if all idle
                            d - System.currentTimeMillis() <= TIMEOUT_SLOP &&
                            CTL.compareAndSet(this, c, nc)) {
                            w.phase = QUIET;
                            //设置任务队列的phase为QUIET，【【且退出该任务队列的驻留线程】】
                            break;
                        }
                    }
                    //阻塞当前工作者等待唤醒，ForkJoinPool 保证至少会有一个工作者线程不会退出（唤醒之后会先设置source为0再回到大循环继续执行）
                    else {
                        LockSupport.park(this);//1-1
                        if (w.phase < 0)          // one spurious wakeup check
                            LockSupport.park(this);//1-2
                    }
                }
                //如果在开放唤醒后真的被别的线程唤醒了phase>=0,或者1-1和1-2中的阻塞结束了，要重新开始执行runWorker。
                // 那么此时线程已经是唤醒状态，不需要再被唤醒
                w.source = 0;                     // disable signal
            }
        }
    }

    /**
     * Scans for and if found executes one or more top-level tasks from a queue.
     *
     * @return true if found an apparently non-empty queue, and
     * possibly ran task(s).
     */
    private boolean scan(WorkQueue w, int r) {
        WorkQueue[] ws; int n;
        if ((ws = workQueues) != null && (n = ws.length) > 0 && w != null) {
            //遍历池中每一个任务队列
            for (int m = n - 1, j = r & m;;) {
                WorkQueue q; int b;
                //如果本次遍历所得任务队列非空（只要池中有任务队列中含任务，该方法就返回true）
                if ((q = ws[j]) != null && q.top != (b = q.base)) {
                    int qid = q.id;
                    ForkJoinTask<?>[] a; int cap, k; ForkJoinTask<?> t;
                    //本次任务队列中含有任务
                    if ((a = q.array) != null && (cap = a.length) > 0) {
                        //获取本次任务队列的头部（base）任务
                        t = (ForkJoinTask<?>)QA.getAcquire(a, k = (cap - 1) & b);
                        //本次任务队列头部未同时被别处修改，头部任务不为空，且置空头部任务成功
                        if (q.base == b++ && t != null &&
                            QA.compareAndSet(a, k, t, null)) {
                            //头部指针加1
                            q.base = b;
                            //设置给定任务队列偷取目标source为本次队列
                            w.source = qid;
                            //如果更新后的本次任务队列的新头部有任务
                            if (a[(cap - 1) & b] != null)
                                signalWork(q);    // help signal if more tasks 根据情况唤醒或新建工作线程
                            w.topLevelExec(t, q,  // random fairness bound
                                           (r | (1 << TOP_BOUND_SHIFT)) & SMASK);
                        }
                    }
                    return true;
                }
                else if (--n > 0)
                    j = (j + 1) & m;
                else
                    break;
            }
        }
        return false;
    }

    /**
     * Helps and/or blocks until the given task is done or timeout.
     * First tries locally helping, then scans other queues for a task
     * produced by one of w's stealers; compensating and blocking if
     * none are found (rescanning if tryCompensate fails).
     *
     * @param w caller
     * @param task the task
     * @param deadline for timed waits, if nonzero
     * @return task status on exit
     */
    final int awaitJoin(WorkQueue w, ForkJoinTask<?> task, long deadline) {
        //join方法归属于task，但是awaitJoin归属于pool，初步判断是因为它需要pool的一些状态而task给不了
        int s = 0;
        int seed = ThreadLocalRandom.nextSecondarySeed();
        /*
         * if(A) { if(B) {C} } else {C}  == if(!A || B) {C} 否或技巧
         */
        /*
         *  工作队列不为 null && 任务不为 null
         *  1）task 不是 CountedCompleter 任务（可继续执行）
         *  2）task 是 CountedCompleter，则尝试窃取和执行目标计算中的任务，直到其完成或无法找到任务为止
         *  3）task 是CountedCompleter任务且没执行成功（可继续执行）
         */
        if (w != null && task != null &&
            (!(task instanceof CountedCompleter) ||
             (s = w.helpCC((CountedCompleter<?>)task, 0, false)) >= 0)) {
            //在任务队列中移除并执行目标task（执行不完没关系，主要是移出来）
            w.tryRemoveAndExec(task);
            int src = w.source, id = w.id;
            //r为奇数，步长为偶数，那么可以得到一个奇数序列
            int r = (seed >>> 16) | 1, step = (seed & ~1) | 2;
            s = task.status;
            //直到task完成为止
            while (s >= 0) {
                WorkQueue[] ws;
                int n = (ws = workQueues) == null ? 0 : ws.length, m = n - 1;
                //n在循环内递减
                while (n > 0) {
                    WorkQueue q; int b;
                    /**
                     *  目标索引 r & m定位到的工作队列不为 null   &&
                     *  此工作队列最近窃取了当前工作队列的任务 &&
                     *  此工作队列有任务待处理 &&
                     *  则帮助其处理任务
                     */
                    if ((q = ws[r & m]) != null && q.source == id &&
                        q.top != (b = q.base)) {
                        ForkJoinTask<?>[] a; int cap, k;
                        int qid = q.id;
                        if ((a = q.array) != null && (cap = a.length) > 0) {
                            ForkJoinTask<?> t = (ForkJoinTask<?>)
                                QA.getAcquire(a, k = (cap - 1) & b);
                            if (q.source == id && q.base == b++ &&
                                t != null && QA.compareAndSet(a, k, t, null)) {
                                q.base = b;
                                w.source = qid;
                                t.doExec();
                                w.source = src;
                            }
                        }
                        break;
                    }
                    else {
                        r += step;
                        --n;
                    }
                }
                if ((s = task.status) < 0)
                    break;
                else if (n == 0) { // empty scan
                    long ms, ns; int block;
                    if (deadline == 0L)
                        ms = 0L;                       // untimed
                    else if ((ns = deadline - System.nanoTime()) <= 0L)
                        break;                         // timeout
                    else if ((ms = TimeUnit.NANOSECONDS.toMillis(ns)) <= 0L)
                        ms = 1L;                       // avoid 0 for timed wait
                    if ((block = tryCompensate(w)) != 0) {
                        task.internalWait(ms);
                        CTL.getAndAdd(this, (block > 0) ? RC_UNIT : 0L);
                    }
                    s = task.status;
                }
            }
        }
        return s;
    }

    /**
     * Runs tasks until {@code isQuiescent()}. Rather than blocking
     * when tasks cannot be found, rescans until all others cannot
     * find tasks either.
     */
    final void helpQuiescePool(WorkQueue w) {
        int prevSrc = w.source;
        int seed = ThreadLocalRandom.nextSecondarySeed();
        //步长必须是奇数（如果是偶数，那步增无法改变奇偶性）
        int r = seed >>> 16, step = r | 1;

        //只有后面的quiet判断满足的时候才会跳出这个循环；
        // released标志了本线程在活跃工作线程这个身份上反复横跳（这个解释可能是错的）
        for (int source = prevSrc, released = -1;;) { // -1 until known
            ForkJoinTask<?> localTask; WorkQueue[] ws;
            //先把给定任务队列中的任务造完
            while ((localTask = w.nextLocalTask()) != null)
                localTask.doExec();


            if (w.phase >= 0 && released == -1)
                released = 1;
            boolean quiet = true, empty = true;
            int n = (ws = workQueues) == null ? 0 : ws.length;

            //随机抽取任务队列池中 一个任务队列，直到取得一个非空任务队列
            for (int m = n - 1; n > 0; r += step, --n) {
                WorkQueue q; int b;
                if ((q = ws[r & m]) != null) {
                    int qs = q.source;
                    //如果任务队列q非空，且有未完成任务
                    if (q.top != (b = q.base)) {
                        //1）任务队列非空，且有未完成任务，则quiet设置为false
                        quiet = empty = false;
                        ForkJoinTask<?>[] a; int cap, k;
                        int qid = q.id;
                        //随机搞到的任务队列中有任务，获取（偷）任务队列头base任务
                        if ((a = q.array) != null && (cap = a.length) > 0) {
                            if (released == 0) {    // increment
                                released = 1;
                                CTL.getAndAdd(this, RC_UNIT);
                            }
                            ForkJoinTask<?> t = (ForkJoinTask<?>)
                                QA.getAcquire(a, k = (cap - 1) & b);
                            if (q.base == b++ && t != null &&
                                QA.compareAndSet(a, k, t, null)) {
                                q.base = b;
                                w.source = qid;
                                t.doExec();
                                w.source = source = prevSrc;
                            }
                        }
                        break;
                    }
                    //执行到这一步，后面还得继续循环找任务队列
                    //2）任务队列q非空，任务数组为空，且任务队列source字段不为QUIET
                    else if ((qs & QUIET) == 0)
                        quiet = false;
                }
            }
            if (quiet) {
                //3）只要随机抽取中有一个非空且含任务的任务队列，就不会执行到这里
                //   只要随机抽取中遇到所有任务队列非空且不含任务且不全是QUIET，就不会执行到这里
                if (released == 0)
                    CTL.getAndAdd(this, RC_UNIT);
                w.source = prevSrc;
                break;
            }
            else if (empty) {
                //4）只要随机抽取中有一个非空且含任务的任务队列，就不会执行到这里，否则就会执行到这里
                if (source != QUIET)
                    w.source = source = QUIET;
                if (released == 1) {                 // decrement
                    released = 0;
                    CTL.getAndAdd(this, RC_MASK & -RC_UNIT);
                }
            }
        }
    }

    /**
     * Scans for and returns a polled task, if available.
     * Used only for untracked polls.
     *
     * @param submissionsOnly if true, only scan submission queues
     */
    private ForkJoinTask<?> pollScan(boolean submissionsOnly) {
        WorkQueue[] ws; int n;
        rescan: while ((mode & STOP) == 0 && (ws = workQueues) != null &&
                      (n = ws.length) > 0) {
            int m = n - 1;
            int r = ThreadLocalRandom.nextSecondarySeed();
            int h = r >>> 16;
            int origin, step;
            if (submissionsOnly) {
                origin = (r & ~1) & m;         // even indices and steps
                step = (h & ~1) | 2;
            }
            else {
                origin = r & m;
                step = h | 1;
            }
            boolean nonempty = false;
            for (int i = origin, oldSum = 0, checkSum = 0;;) {
                WorkQueue q;
                if ((q = ws[i]) != null) {
                    int b; ForkJoinTask<?> t;
                    if (q.top - (b = q.base) > 0) {
                        nonempty = true;
                        if ((t = q.poll()) != null)
                            return t;
                    }
                    else
                        checkSum += b + q.id;
                }
                if ((i = (i + step) & m) == origin) {
                    if (!nonempty && oldSum == (oldSum = checkSum))
                        break rescan;
                    checkSum = 0;
                    nonempty = false;
                }
            }
        }
        return null;
    }

    /**
     * Gets and removes a local or stolen task for the given worker.
     *
     * @return a task, if available
     */
    final ForkJoinTask<?> nextTaskFor(WorkQueue w) {
        ForkJoinTask<?> t;
        if (w == null || (t = w.nextLocalTask()) == null)
            t = pollScan(false);
        return t;
    }

    // External operations

    /**
     * Adds the given task to a submission queue at submitter's
     * current queue, creating one if null or contended.
     *
     * @param task the task. Caller must ensure non-null.
     */
    final void externalPush(ForkJoinTask<?> task) {
        int r;                                // initialize caller's probe
        if ((r = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();
            r = ThreadLocalRandom.getProbe();
        }
        for (;;) {
            WorkQueue q;
            int md = mode, n;
            WorkQueue[] ws = workQueues;
            //如果线程池在SHUTDOWN过程中，或者任务队列数组为空则抛出特定异常
            if ((md & SHUTDOWN) != 0 || ws == null || (n = ws.length) <= 0)
                throw new RejectedExecutionException();
            //计算r对应的安全索引对应的任务队列为空
            //添加一个任务队列
            else if ((q = ws[(n - 1) & r & SQMASK]) == null) { // add queue
                //qid为不安全的r通过配置一些状态位计算得到；
                // qid必然为偶数，qid代表的队列为共享队列,满足队列静止，先进后出FILO
                // (n - 1) & r & SQMASK 和 qid 实际上是一致的，这也意味着后面的i对应的槽应该也是空
                int qid = (r | QUIET) & ~(FIFO | OWNED);
                Object lock = workerNamePrefix;
                ForkJoinTask<?>[] qa =
                    new ForkJoinTask<?>[INITIAL_QUEUE_CAPACITY];
                //下面就是初始化共享队列
                q = new WorkQueue(this, null);
                q.array = qa;
                q.id = qid;
                //此时phase为默认值0，表示共享队列未上锁；source为QUIET表示偷取对象（未绑定线程，且没任务）->（存疑）
                q.source = QUIET;
                if (lock != null) {     // unless disabled, lock pool to install
                    synchronized (lock) {
                        WorkQueue[] vs; int i, vn;
                        //SQMASK为64个偶数槽中数值最大的槽126
                        //如果别的线程已经添加了一个任务队列，那本线程就不继续添加了
                        if ((vs = workQueues) != null && (vn = vs.length) > 0 &&
                            vs[i = qid & (vn - 1) & SQMASK] == null)
                            vs[i] = q;  // else another thread already installed
                    }
                }
            }
            //尝试锁住r计算获得，或新建的任务队列，如果失败则重置r接着拿新的任务队列
            else if (!q.tryLockPhase()) // move if busy
                r = ThreadLocalRandom.advanceProbe(r);
            //锁住了获得的任务队列，
            else {
                if (q.lockedPush(task))
                    signalWork(null);
                return;
            }
        }
    }

    /**
     * Pushes a possibly-external submission.
     */
    private <T> ForkJoinTask<T> externalSubmit(ForkJoinTask<T> task) {
        Thread t; ForkJoinWorkerThread w; WorkQueue q;
        if (task == null)
            throw new NullPointerException();
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
            (w = (ForkJoinWorkerThread)t).pool == this &&
            (q = w.workQueue) != null)
            q.push(task);
        else
            externalPush(task);
        return task;
    }

    /**
     * Returns common pool queue for an external thread.
     */
    static WorkQueue commonSubmitterQueue() {
        ForkJoinPool p = common;
        int r = ThreadLocalRandom.getProbe();
        WorkQueue[] ws; int n;
        return (p != null && (ws = p.workQueues) != null &&
                (n = ws.length) > 0) ?
            ws[(n - 1) & r & SQMASK] : null;
    }

    /**
     * Performs tryUnpush for an external submitter.
     */
    final boolean tryExternalUnpush(ForkJoinTask<?> task) {
        int r = ThreadLocalRandom.getProbe();
        WorkQueue[] ws; WorkQueue w; int n;
        return ((ws = workQueues) != null &&
                (n = ws.length) > 0 &&
                (w = ws[(n - 1) & r & SQMASK]) != null &&
                w.tryLockedUnpush(task));
    }

    /**
     * Performs helpComplete for an external submitter.
     */
    final int externalHelpComplete(CountedCompleter<?> task, int maxTasks) {
        int r = ThreadLocalRandom.getProbe();
        WorkQueue[] ws; WorkQueue w; int n;
        return ((ws = workQueues) != null && (n = ws.length) > 0 &&
                (w = ws[(n - 1) & r & SQMASK]) != null) ?
            w.helpCC(task, maxTasks, true) : 0;
    }

    /**
     * Tries to steal and run tasks within the target's computation.
     * The maxTasks argument supports external usages; internal calls
     * use zero, allowing unbounded steps (external calls trap
     * non-positive values).
     *
     * @param w caller
     * @param maxTasks if non-zero, the maximum number of other tasks to run
     * @return task status on exit
     */
    final int helpComplete(WorkQueue w, CountedCompleter<?> task,
                           int maxTasks) {
        return (w == null) ? 0 : w.helpCC(task, maxTasks, false);
    }

    /**
     * Returns a cheap heuristic guide for task partitioning when
     * programmers, frameworks, tools, or languages have little or no
     * idea about task granularity.  In essence, by offering this
     * method, we ask users only about tradeoffs in overhead vs
     * expected throughput and its variance, rather than how finely to
     * partition tasks.
     *
     * In a steady state strict (tree-structured) computation, each
     * thread makes available for stealing enough tasks for other
     * threads to remain active. Inductively, if all threads play by
     * the same rules, each thread should make available only a
     * constant number of tasks.
     *
     * The minimum useful constant is just 1. But using a value of 1
     * would require immediate replenishment upon each steal to
     * maintain enough tasks, which is infeasible.  Further,
     * partitionings/granularities of offered tasks should minimize
     * steal rates, which in general means that threads nearer the top
     * of computation tree should generate more than those nearer the
     * bottom. In perfect steady state, each thread is at
     * approximately the same level of computation tree. However,
     * producing extra tasks amortizes the uncertainty of progress and
     * diffusion assumptions.
     *
     * So, users will want to use values larger (but not much larger)
     * than 1 to both smooth over transient shortages and hedge
     * against uneven progress; as traded off against the cost of
     * extra task overhead. We leave the user to pick a threshold
     * value to compare with the results of this call to guide
     * decisions, but recommend values such as 3.
     *
     * When all threads are active, it is on average OK to estimate
     * surplus strictly locally. In steady-state, if one thread is
     * maintaining say 2 surplus tasks, then so are others. So we can
     * just use estimated queue length.  However, this strategy alone
     * leads to serious mis-estimates in some non-steady-state
     * conditions (ramp-up, ramp-down, other stalls). We can detect
     * many of these by further considering the number of "idle"
     * threads, that are known to have zero queued tasks, so
     * compensate by a factor of (#idle/#active) threads.
     */
    static int getSurplusQueuedTaskCount() {
        Thread t; ForkJoinWorkerThread wt; ForkJoinPool pool; WorkQueue q;
        if (((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) &&
            (pool = (wt = (ForkJoinWorkerThread)t).pool) != null &&
            (q = wt.workQueue) != null) {
            int p = pool.mode & SMASK;
            int a = p + (int)(pool.ctl >> RC_SHIFT);
            int n = q.top - q.base;
            return n - (a > (p >>>= 1) ? 0 :
                        a > (p >>>= 1) ? 1 :
                        a > (p >>>= 1) ? 2 :
                        a > (p >>>= 1) ? 4 :
                        8);
        }
        return 0;
    }

    // Termination

    /**
     * Possibly initiates and/or completes termination.
     *
     * @param now if true, unconditionally terminate, else only
     * if no work and no active workers
     *            true：表示无条件终止
     *            false：表示线程池无任务或无活跃工作者线程之后终止
     * @param enable if true, terminate when next possible
     * @return true if terminating or terminated
     */
    private boolean tryTerminate(boolean now, boolean enable) {
        int md; // 3 phases: try to set SHUTDOWN, then STOP, then TERMINATED

        //如果线程池还没有进入SHUTDOWN阶段，且满足enable为false和当前线程池为公共线程池中的一个条件，则终止失败
        //否则设置SHUTDOWN标志直到成功
        while (((md = mode) & SHUTDOWN) == 0) {
            if (!enable || this == common)        // cannot shutdown
                return false;
            else
                MODE.compareAndSet(this, md, md | SHUTDOWN);
        }

        //如果线程池还没有进入STOP阶段
        while (((md = mode) & STOP) == 0) {       // try to initiate termination
            //如果需要等待队列静止及任务为空
            if (!now) {                           // check if quiescent & empty
                for (long oldSum = 0L;;) {        // repeat until stable
                    boolean running = false;
                    long checkSum = ctl;
                    WorkQueue[] ws = workQueues;
                    //1）活跃线程数大于预期活跃线程数，则设置running为true
                    if ((md & SMASK) + (int)(checkSum >> RC_SHIFT) > 0)
                        running = true;
                    //2）活跃线程数已经少于并行系数但池中还有任务队列
                    else if (ws != null) {
                        WorkQueue w;
                        //遍历池中所有任务队列
                        for (int i = 0; i < ws.length; ++i) {
                            if ((w = ws[i]) != null) {
                                int s = w.source, p = w.phase;
                                int d = w.id, b = w.base;
                                //如果遍历本次任务队列有任务或者【队列是工作队列（非共享队列）且未阻塞或偷取目标未阻塞】
                                //说明还有任务队列在执行，或者正在扫描或者有工作未完成，则设置running为true并跳出
                                if (b != w.top ||
                                    ((d & 1) == 1 && (s >= 0 || p >= 0))) {
                                    running = true;
                                    break;     // working, scanning, or have work
                                }
                                /*要满足计算式为0，需要for循环中每个子checkSum都为0：
                                 * 1）任务队列数组的每一个非空任务队列没有在偷任务
                                 * 2）任务队列数组的每一个非空任务为共享队列且未上锁
                                 * 3）任务队列数组的每一个非空任务id为0
                                 * 4）任务队列数组的每一个非空任务base为0
                                 */
                                checkSum += (((long)s << 48) + ((long)p << 32) +
                                             ((long)b << 16) + (long)d);
                            }
                        }
                    }
                    //在别的线程中线程池已经提前进入STOP阶段，那么本线程可以跳出循环
                    if (((md = mode) & STOP) != 0)
                        break;                 // already triggered
                    //如果还有很多活跃线程，或者存在一个及以上活跃任务，则终止失败
                    else if (running)
                        return false;
                    //这里会不停地循环，直到checkSum四个部分都为0为止
                    else if (workQueues == ws && oldSum == (oldSum = checkSum))
                        break;
                }
            }
            //线程池进入STOP阶段
            if ((md & STOP) == 0)
                MODE.compareAndSet(this, md, md | STOP);
        }

        //如果线程池还没有进入TERMINATED阶段
        while (((md = mode) & TERMINATED) == 0) { // help terminate others
            for (long oldSum = 0L;;) {            // repeat until stable
                WorkQueue[] ws; WorkQueue w;
                long checkSum = ctl;
                if ((ws = workQueues) != null) {
                    for (int i = 0; i < ws.length; ++i) {
                        if ((w = ws[i]) != null) {
                            ForkJoinWorkerThread wt = w.owner;
                            //取消所有任务
                            w.cancelAll();        // clear queues
                            //如果是工作队列
                            if (wt != null) {
                                try {             // unblock join or park
                                    //中断工作者线程（如果处于join或者park阻塞中，则会解除阻塞）
                                    wt.interrupt();
                                } catch (Throwable ignore) {
                                }
                            }
                            //累加校验和
                            checkSum += ((long)w.phase << 32) + w.base;
                        }
                    }
                }
                if (((md = mode) & TERMINATED) != 0 ||
                    (workQueues == ws && oldSum == (oldSum = checkSum)))
                    break;
            }
            //如果别的线程捷足先登，抢先一步设置TERMINATED状态，则退出
            if ((md & TERMINATED) != 0)
                break;
            //如果实际常驻线程比较多，则直接退出
            else if ((md & SMASK) + (short)(ctl >>> TC_SHIFT) > 0)
                break;
            //执行到这里就可以设置TERMINATED状态了
            else if (MODE.compareAndSet(this, md, md | TERMINATED)) {
                synchronized (this) {
                    //在awaitTermination操作中有些线程在阻塞等待，调用下面方法会提前唤醒它并校验终止标志
                    notifyAll();                  // for awaitTermination
                }
                break;
            }
        }
        return true;
    }

    // Exported methods

    // Constructors

    /**
     * Creates a {@code ForkJoinPool} with parallelism equal to {@link
     * java.lang.Runtime#availableProcessors}, using defaults for all
     * other parameters (see {@link #ForkJoinPool(int,
     * ForkJoinWorkerThreadFactory, UncaughtExceptionHandler, boolean,
     * int, int, int, Predicate, long, TimeUnit)}).
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool() {
        this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
             defaultForkJoinWorkerThreadFactory, null, false,
             0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a {@code ForkJoinPool} with the indicated parallelism
     * level, using defaults for all other parameters (see {@link
     * #ForkJoinPool(int, ForkJoinWorkerThreadFactory,
     * UncaughtExceptionHandler, boolean, int, int, int, Predicate,
     * long, TimeUnit)}).
     *
     * @param parallelism the parallelism level
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false,
             0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters (using
     * defaults for others -- see {@link #ForkJoinPool(int,
     * ForkJoinWorkerThreadFactory, UncaughtExceptionHandler, boolean,
     * int, int, int, Predicate, long, TimeUnit)}).
     *
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     * @param factory the factory for creating new threads. For default value,
     * use {@link #defaultForkJoinWorkerThreadFactory}.
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while executing
     * tasks. For default value, use {@code null}.
     * @param asyncMode if true,
     * establishes local first-in-first-out scheduling mode for forked
     * tasks that are never joined. This mode may be more appropriate
     * than default locally stack-based mode in applications in which
     * worker threads only process event-style asynchronous tasks.
     * For default value, use {@code false}.
     * @throws IllegalArgumentException if parallelism less than or
     *         equal to zero, or greater than implementation limit
     * @throws NullPointerException if the factory is null
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        UncaughtExceptionHandler handler,
                        boolean asyncMode) {
        this(parallelism, factory, handler, asyncMode,
             0, MAX_CAP, 1, null, DEFAULT_KEEPALIVE, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a {@code ForkJoinPool} with the given parameters.
     *
     * @param parallelism the parallelism level. For default value,
     * use {@link java.lang.Runtime#availableProcessors}.
     *
     * @param factory the factory for creating new threads. For
     * default value, use {@link #defaultForkJoinWorkerThreadFactory}.
     *
     * @param handler the handler for internal worker threads that
     * terminate due to unrecoverable errors encountered while
     * executing tasks. For default value, use {@code null}.
     *
     * @param asyncMode if true, establishes local first-in-first-out
     * scheduling mode for forked tasks that are never joined. This
     * mode may be more appropriate than default locally stack-based
     * mode in applications in which worker threads only process
     * event-style asynchronous tasks.  For default value, use {@code
     * false}.
     *
     * @param corePoolSize the number of threads to keep in the pool
     * (unless timed out after an elapsed keep-alive). Normally (and
     * by default) this is the same value as the parallelism level,
     * but may be set to a larger value to reduce dynamic overhead if
     * tasks regularly block. Using a smaller value (for example
     * {@code 0}) has the same effect as the default.
     *
     * @param maximumPoolSize the maximum number of threads allowed.
     * When the maximum is reached, attempts to replace blocked
     * threads fail.  (However, because creation and termination of
     * different threads may overlap, and may be managed by the given
     * thread factory, this value may be transiently exceeded.)  To
     * arrange the same value as is used by default for the common
     * pool, use {@code 256} plus the {@code parallelism} level. (By
     * default, the common pool allows a maximum of 256 spare
     * threads.)  Using a value (for example {@code
     * Integer.MAX_VALUE}) larger than the implementation's total
     * thread limit has the same effect as using this limit (which is
     * the default).
     *
     * @param minimumRunnable the minimum allowed number of core
     * threads not blocked by a join or {@link ManagedBlocker}.  To
     * ensure progress, when too few unblocked threads exist and
     * unexecuted tasks may exist, new threads are constructed, up to
     * the given maximumPoolSize.  For the default value, use {@code
     * 1}, that ensures liveness.  A larger value might improve
     * throughput in the presence of blocked activities, but might
     * not, due to increased overhead.  A value of zero may be
     * acceptable when submitted tasks cannot have dependencies
     * requiring additional threads.
     *
     * @param saturate if non-null, a predicate invoked upon attempts
     * to create more than the maximum total allowed threads.  By
     * default, when a thread is about to block on a join or {@link
     * ManagedBlocker}, but cannot be replaced because the
     * maximumPoolSize would be exceeded, a {@link
     * RejectedExecutionException} is thrown.  But if this predicate
     * returns {@code true}, then no exception is thrown, so the pool
     * continues to operate with fewer than the target number of
     * runnable threads, which might not ensure progress.
     *
     * @param keepAliveTime the elapsed time since last use before
     * a thread is terminated (and then later replaced if needed).
     * For the default value, use {@code 60, TimeUnit.SECONDS}.
     *
     * @param unit the time unit for the {@code keepAliveTime} argument
     *
     * @throws IllegalArgumentException if parallelism is less than or
     *         equal to zero, or is greater than implementation limit,
     *         or if maximumPoolSize is less than parallelism,
     *         of if the keepAliveTime is less than or equal to zero.
     * @throws NullPointerException if the factory is null
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     * @since 9
     */
    public ForkJoinPool(int parallelism,
                        ForkJoinWorkerThreadFactory factory,
                        UncaughtExceptionHandler handler,
                        boolean asyncMode,
                        int corePoolSize,
                        int maximumPoolSize,
                        int minimumRunnable,
                        Predicate<? super ForkJoinPool> saturate,
                        long keepAliveTime,
                        TimeUnit unit) {
        // check, encode, pack parameters
        if (parallelism <= 0 || parallelism > MAX_CAP ||
            maximumPoolSize < parallelism || keepAliveTime <= 0L)
            throw new IllegalArgumentException();
        if (factory == null)
            throw new NullPointerException();
        long ms = Math.max(unit.toMillis(keepAliveTime), TIMEOUT_SLOP);

        //核心池大小的安全表示（客户传入的corePoolSize值可能范围不安全）
        int corep = Math.min(Math.max(corePoolSize, parallelism), MAX_CAP);
        long c = ((((long)(-corep)       << TC_SHIFT) & TC_MASK) |
                  (((long)(-parallelism) << RC_SHIFT) & RC_MASK));
        int m = parallelism | (asyncMode ? FIFO : 0);
        //储备线程数：除去并发系数外还能创建的线程数量（一个创建上限类型的值）
        int maxSpares = Math.min(maximumPoolSize, MAX_CAP) - parallelism;
        //最小的可用线程数，minimumRunnable的安全表示（客户传入的mimimumRunnable值可能范围不安全）
        int minAvail = Math.min(Math.max(minimumRunnable, 0), MAX_CAP);
        /*
         * 低16位表示除开并行系数外至少应该有的线程数（为了满足最小可用限制）
         * 高16位表示除开并行系数外最大可创建线程数
         * 该处为forkJoinPool的bounds值初始化位置，后续应该根据线程创建销毁动态维护
         */
        int b = ((minAvail - parallelism) & SMASK) | (maxSpares << SWIDTH);
        int n = (parallelism > 1) ? parallelism - 1 : 1; // at least 2 slots
        n |= n >>> 1; n |= n >>> 2; n |= n >>> 4; n |= n >>> 8; n |= n >>> 16;
        n = (n + 1) << 1; // power of two, including space for submission queues

        this.workerNamePrefix = "ForkJoinPool-" + nextPoolId() + "-worker-";
        this.workQueues = new WorkQueue[n];
        this.factory = factory;
        this.ueh = handler;
        this.saturate = saturate;
        this.keepAlive = ms;
        this.bounds = b;
        this.mode = m;
        this.ctl = c;
        checkPermission();
    }

    private static Object newInstanceFromSystemProperty(String property)
        throws ReflectiveOperationException {
        String className = System.getProperty(property);
        return (className == null)
            ? null
            : ClassLoader.getSystemClassLoader().loadClass(className)
            .getConstructor().newInstance();
    }

    /**
     * Constructor for common pool using parameters possibly
     * overridden by system properties
     */
    private ForkJoinPool(byte forCommonPoolOnly) {
        int parallelism = -1;
        ForkJoinWorkerThreadFactory fac = null;
        UncaughtExceptionHandler handler = null;
        try {  // ignore exceptions in accessing/parsing properties
            String pp = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.parallelism");
            if (pp != null)
                parallelism = Integer.parseInt(pp);
            fac = (ForkJoinWorkerThreadFactory) newInstanceFromSystemProperty(
                "java.util.concurrent.ForkJoinPool.common.threadFactory");
            handler = (UncaughtExceptionHandler) newInstanceFromSystemProperty(
                "java.util.concurrent.ForkJoinPool.common.exceptionHandler");
        } catch (Exception ignore) {
        }

        if (fac == null) {
            if (System.getSecurityManager() == null)
                fac = defaultForkJoinWorkerThreadFactory;
            else // use security-managed default
                fac = new InnocuousForkJoinWorkerThreadFactory();
        }
        if (parallelism < 0 && // default 1 less than #cores
            (parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0)
            parallelism = 1;
        if (parallelism > MAX_CAP)
            parallelism = MAX_CAP;

        long c = ((((long)(-parallelism) << TC_SHIFT) & TC_MASK) |
                  (((long)(-parallelism) << RC_SHIFT) & RC_MASK));
        int b = ((1 - parallelism) & SMASK) | (COMMON_MAX_SPARES << SWIDTH);
        int n = (parallelism > 1) ? parallelism - 1 : 1;
        n |= n >>> 1; n |= n >>> 2; n |= n >>> 4; n |= n >>> 8; n |= n >>> 16;
        n = (n + 1) << 1;

        this.workerNamePrefix = "ForkJoinPool.commonPool-worker-";
        this.workQueues = new WorkQueue[n];
        this.factory = fac;
        this.ueh = handler;
        this.saturate = null;
        this.keepAlive = DEFAULT_KEEPALIVE;
        this.bounds = b;
        this.mode = parallelism;
        this.ctl = c;
    }

    /**
     * Returns the common pool instance. This pool is statically
     * constructed; its run state is unaffected by attempts to {@link
     * #shutdown} or {@link #shutdownNow}. However this pool and any
     * ongoing processing are automatically terminated upon program
     * {@link System#exit}.  Any program that relies on asynchronous
     * task processing to complete before program termination should
     * invoke {@code commonPool().}{@link #awaitQuiescence awaitQuiescence},
     * before exit.
     *
     * @return the common pool instance
     * @since 1.8
     */
    public static ForkJoinPool commonPool() {
        // assert common != null : "static init error";
        return common;
    }

    // Execution methods

    /**
     * Performs the given task, returning its result upon completion.
     * If the computation encounters an unchecked Exception or Error,
     * it is rethrown as the outcome of this invocation.  Rethrown
     * exceptions behave in the same way as regular exceptions, but,
     * when possible, contain stack traces (as displayed for example
     * using {@code ex.printStackTrace()}) of both the current thread
     * as well as the thread actually encountering the exception;
     * minimally only the latter.
     *
     * @param task the task
     * @param <T> the type of the task's result
     * @return the task's result
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> T invoke(ForkJoinTask<T> task) {
        if (task == null)
            throw new NullPointerException();
        externalSubmit(task);
        return task.join();
    }

    /**
     * Arranges for (asynchronous) execution of the given task.
     *
     * @param task the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(ForkJoinTask<?> task) {
        externalSubmit(task);
    }

    // AbstractExecutorService methods

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        ForkJoinTask<?> job;
        if (task instanceof ForkJoinTask<?>) // avoid re-wrap
            job = (ForkJoinTask<?>) task;
        else
            job = new ForkJoinTask.RunnableExecuteAction(task);
        externalSubmit(job);
    }

    /**
     * Submits a ForkJoinTask for execution.
     *
     * @param task the task to submit
     * @param <T> the type of the task's result
     * @return the task
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        return externalSubmit(task);
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        return externalSubmit(new ForkJoinTask.AdaptedCallable<T>(task));
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        return externalSubmit(new ForkJoinTask.AdaptedRunnable<T>(task, result));
    }

    /**
     * @throws NullPointerException if the task is null
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     */
    @SuppressWarnings("unchecked")
    public ForkJoinTask<?> submit(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        return externalSubmit((task instanceof ForkJoinTask<?>)
            ? (ForkJoinTask<Void>) task // avoid re-wrap
            : new ForkJoinTask.AdaptedRunnableAction(task));
    }

    /**
     * @throws NullPointerException       {@inheritDoc}
     * @throws RejectedExecutionException {@inheritDoc}
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        // In previous versions of this class, this method constructed
        // a task to run ForkJoinTask.invokeAll, but now external
        // invocation of multiple tasks is at least as efficient.
        ArrayList<Future<T>> futures = new ArrayList<>(tasks.size());

        try {
            for (Callable<T> t : tasks) {
                ForkJoinTask<T> f = new ForkJoinTask.AdaptedCallable<T>(t);
                futures.add(f);
                externalSubmit(f);
            }
            for (int i = 0, size = futures.size(); i < size; i++)
                ((ForkJoinTask<?>)futures.get(i)).quietlyJoin();
            return futures;
        } catch (Throwable t) {
            for (int i = 0, size = futures.size(); i < size; i++)
                futures.get(i).cancel(false);
            throw t;
        }
    }

    /**
     * Returns the factory used for constructing new workers.
     *
     * @return the factory used for constructing new workers
     */
    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    /**
     * Returns the handler for internal worker threads that terminate
     * due to unrecoverable errors encountered while executing tasks.
     *
     * @return the handler, or {@code null} if none
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return ueh;
    }

    /**
     * Returns the targeted parallelism level of this pool.
     *
     * @return the targeted parallelism level of this pool
     */
    public int getParallelism() {
        int par = mode & SMASK;
        return (par > 0) ? par : 1;
    }

    /**
     * Returns the targeted parallelism level of the common pool.
     *
     * @return the targeted parallelism level of the common pool
     * @since 1.8
     */
    public static int getCommonPoolParallelism() {
        return COMMON_PARALLELISM;
    }

    /**
     * Returns the number of worker threads that have started but not
     * yet terminated.  The result returned by this method may differ
     * from {@link #getParallelism} when threads are created to
     * maintain parallelism when others are cooperatively blocked.
     *
     * @return the number of worker threads
     */
    public int getPoolSize() {
        return ((mode & SMASK) + (short)(ctl >>> TC_SHIFT));
    }

    /**
     * Returns {@code true} if this pool uses local first-in-first-out
     * scheduling mode for forked tasks that are never joined.
     *
     * @return {@code true} if this pool uses async mode
     */
    public boolean getAsyncMode() {
        return (mode & FIFO) != 0;
    }

    /**
     * Returns an estimate of the number of worker threads that are
     * not blocked waiting to join tasks or for other managed
     * synchronization. This method may overestimate the
     * number of running threads.
     *
     * @return the number of worker threads
     */
    public int getRunningThreadCount() {
        WorkQueue[] ws; WorkQueue w;
        VarHandle.acquireFence();
        int rc = 0;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && w.isApparentlyUnblocked())
                    ++rc;
            }
        }
        return rc;
    }

    /**
     * Returns an estimate of the number of threads that are currently
     * stealing or executing tasks. This method may overestimate the
     * number of active threads.
     *
     * @return the number of active threads
     */
    public int getActiveThreadCount() {
        int r = (mode & SMASK) + (int)(ctl >> RC_SHIFT);
        return (r <= 0) ? 0 : r; // suppress momentarily negative values
    }

    /**
     * Returns {@code true} if all worker threads are currently idle.
     * An idle worker is one that cannot obtain a task to execute
     * because none are available to steal from other threads, and
     * there are no pending submissions to the pool. This method is
     * conservative; it might not return {@code true} immediately upon
     * idleness of all threads, but will eventually become true if
     * threads remain inactive.
     *
     * @return {@code true} if all threads are currently idle
     */
    public boolean isQuiescent() {
        for (;;) {
            long c = ctl;
            int md = mode, pc = md & SMASK;
            int tc = pc + (short)(c >>> TC_SHIFT);
            int rc = pc + (int)(c >> RC_SHIFT);
            if ((md & (STOP | TERMINATED)) != 0)
                return true;
            else if (rc > 0)
                return false;
            else {
                WorkQueue[] ws; WorkQueue v;
                if ((ws = workQueues) != null) {
                    for (int i = 1; i < ws.length; i += 2) {
                        if ((v = ws[i]) != null) {
                            if (v.source > 0)
                                return false;
                            --tc;
                        }
                    }
                }
                if (tc == 0 && ctl == c)
                    return true;
            }
        }
    }

    /**
     * Returns an estimate of the total number of completed tasks that
     * were executed by a thread other than their submitter. The
     * reported value underestimates the actual total number of steals
     * when the pool is not quiescent. This value may be useful for
     * monitoring and tuning fork/join programs: in general, steal
     * counts should be high enough to keep threads busy, but low
     * enough to avoid overhead and contention across threads.
     *
     * @return the number of steals
     */
    public long getStealCount() {
        long count = stealCount;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += (long)w.nsteals & 0xffffffffL;
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the total number of tasks currently held
     * in queues by worker threads (but not including tasks submitted
     * to the pool that have not begun executing). This value is only
     * an approximation, obtained by iterating across all threads in
     * the pool. This method may be useful for tuning task
     * granularities.
     *
     * @return the number of queued tasks
     */
    public long getQueuedTaskCount() {
        WorkQueue[] ws; WorkQueue w;
        VarHandle.acquireFence();
        int count = 0;
        if ((ws = workQueues) != null) {
            for (int i = 1; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns an estimate of the number of tasks submitted to this
     * pool that have not yet begun executing.  This method may take
     * time proportional to the number of submissions.
     *
     * @return the number of queued submissions
     */
    public int getQueuedSubmissionCount() {
        WorkQueue[] ws; WorkQueue w;
        VarHandle.acquireFence();
        int count = 0;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null)
                    count += w.queueSize();
            }
        }
        return count;
    }

    /**
     * Returns {@code true} if there are any tasks submitted to this
     * pool that have not yet begun executing.
     *
     * @return {@code true} if there are any queued submissions
     */
    public boolean hasQueuedSubmissions() {
        WorkQueue[] ws; WorkQueue w;
        VarHandle.acquireFence();
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; i += 2) {
                if ((w = ws[i]) != null && !w.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Removes and returns the next unexecuted submission if one is
     * available.  This method may be useful in extensions to this
     * class that re-assign work in systems with multiple pools.
     *
     * @return the next submission, or {@code null} if none
     */
    protected ForkJoinTask<?> pollSubmission() {
        return pollScan(true);
    }

    /**
     * Removes all available unexecuted submitted and forked tasks
     * from scheduling queues and adds them to the given collection,
     * without altering their execution status. These may include
     * artificially generated or wrapped tasks. This method is
     * designed to be invoked only when the pool is known to be
     * quiescent. Invocations at other times may not remove all
     * tasks. A failure encountered while attempting to add elements
     * to collection {@code c} may result in elements being in
     * neither, either or both collections when the associated
     * exception is thrown.  The behavior of this operation is
     * undefined if the specified collection is modified while the
     * operation is in progress.
     *
     * @param c the collection to transfer elements into
     * @return the number of elements transferred
     */
    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        WorkQueue[] ws; WorkQueue w; ForkJoinTask<?> t;
        VarHandle.acquireFence();
        int count = 0;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    while ((t = w.poll()) != null) {
                        c.add(t);
                        ++count;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state, parallelism level, and
     * worker and task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        // Use a single pass through workQueues to collect counts
        int md = mode; // read volatile fields first
        long c = ctl;
        long st = stealCount;
        long qt = 0L, qs = 0L; int rc = 0;
        WorkQueue[] ws; WorkQueue w;
        if ((ws = workQueues) != null) {
            for (int i = 0; i < ws.length; ++i) {
                if ((w = ws[i]) != null) {
                    int size = w.queueSize();
                    if ((i & 1) == 0)
                        qs += size;
                    else {
                        qt += size;
                        st += (long)w.nsteals & 0xffffffffL;
                        if (w.isApparentlyUnblocked())
                            ++rc;
                    }
                }
            }
        }

        int pc = (md & SMASK);
        int tc = pc + (short)(c >>> TC_SHIFT);
        int ac = pc + (int)(c >> RC_SHIFT);
        if (ac < 0) // ignore transient negative
            ac = 0;
        String level = ((md & TERMINATED) != 0 ? "Terminated" :
                        (md & STOP)       != 0 ? "Terminating" :
                        (md & SHUTDOWN)   != 0 ? "Shutting down" :
                        "Running");
        return super.toString() +
            "[" + level +
            ", parallelism = " + pc +
            ", size = " + tc +
            ", active = " + ac +
            ", running = " + rc +
            ", steals = " + st +
            ", tasks = " + qt +
            ", submissions = " + qs +
            "]";
    }

    /**
     * Possibly initiates an orderly shutdown in which previously
     * submitted tasks are executed, but no new tasks will be
     * accepted. Invocation has no effect on execution state if this
     * is the {@link #commonPool()}, and no additional effect if
     * already shut down.  Tasks that are in the process of being
     * submitted concurrently during the course of this method may or
     * may not be rejected.
     *
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public void shutdown() {
        checkPermission();
        tryTerminate(false, true);
    }

    /**
     * Possibly attempts to cancel and/or stop all tasks, and reject
     * all subsequently submitted tasks.  Invocation has no effect on
     * execution state if this is the {@link #commonPool()}, and no
     * additional effect if already shut down. Otherwise, tasks that
     * are in the process of being submitted or executed concurrently
     * during the course of this method may or may not be
     * rejected. This method cancels both existing and unexecuted
     * tasks, in order to permit termination in the presence of task
     * dependencies. So the method always returns an empty list
     * (unlike the case for some other Executors).
     *
     * @return an empty list
     * @throws SecurityException if a security manager exists and
     *         the caller is not permitted to modify threads
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")}
     */
    public List<Runnable> shutdownNow() {
        checkPermission();
        tryTerminate(true, true);
        return Collections.emptyList();
    }

    /**
     * Returns {@code true} if all tasks have completed following shut down.
     *
     * @return {@code true} if all tasks have completed following shut down
     */
    public boolean isTerminated() {
        return (mode & TERMINATED) != 0;
    }

    /**
     * Returns {@code true} if the process of termination has
     * commenced but not yet completed.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, or are waiting for I/O,
     * causing this executor not to properly terminate. (See the
     * advisory notes for class {@link ForkJoinTask} stating that
     * tasks should not normally entail blocking operations.  But if
     * they do, they must abort them on interrupt.)
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int md = mode;
        return (md & STOP) != 0 && (md & TERMINATED) == 0;
    }

    /**
     * Returns {@code true} if this pool has been shut down.
     *
     * @return {@code true} if this pool has been shut down
     */
    public boolean isShutdown() {
        return (mode & SHUTDOWN) != 0;
    }

    /**
     * Blocks until all tasks have completed execution after a
     * shutdown request, or the timeout occurs, or the current thread
     * is interrupted, whichever happens first. Because the {@link
     * #commonPool()} never terminates until program shutdown, when
     * applied to the common pool, this method is equivalent to {@link
     * #awaitQuiescence(long, TimeUnit)} but always returns {@code false}.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (this == common) {
            awaitQuiescence(timeout, unit);
            return false;
        }
        long nanos = unit.toNanos(timeout);
        if (isTerminated())
            return true;
        if (nanos <= 0L)
            return false;
        long deadline = System.nanoTime() + nanos;
        synchronized (this) {
            for (;;) {
                if (isTerminated())
                    return true;
                if (nanos <= 0L)
                    return false;
                long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                wait(millis > 0L ? millis : 1L);
                nanos = deadline - System.nanoTime();
            }
        }
    }

    /**
     * If called by a ForkJoinTask operating in this pool, equivalent
     * in effect to {@link ForkJoinTask#helpQuiesce}. Otherwise,
     * waits and/or attempts to assist performing tasks until this
     * pool {@link #isQuiescent} or the indicated timeout elapses.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if quiescent; {@code false} if the
     * timeout elapsed.
     */
    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long nanos = unit.toNanos(timeout);
        ForkJoinWorkerThread wt;
        Thread thread = Thread.currentThread();
        if ((thread instanceof ForkJoinWorkerThread) &&
            (wt = (ForkJoinWorkerThread)thread).pool == this) {
            helpQuiescePool(wt.workQueue);
            return true;
        }
        else {
            for (long startTime = System.nanoTime();;) {
                ForkJoinTask<?> t;
                if ((t = pollScan(false)) != null)
                    t.doExec();
                else if (isQuiescent())
                    return true;
                else if ((System.nanoTime() - startTime) > nanos)
                    return false;
                else
                    Thread.yield(); // cannot block
            }
        }
    }

    /**
     * Waits and/or attempts to assist performing tasks indefinitely
     * until the {@link #commonPool()} {@link #isQuiescent}.
     */
    static void quiesceCommonPool() {
        common.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Interface for extending managed parallelism for tasks running
     * in {@link ForkJoinPool}s.
     *
     * <p>A {@code ManagedBlocker} provides two methods.  Method
     * {@link #isReleasable} must return {@code true} if blocking is
     * not necessary. Method {@link #block} blocks the current thread
     * if necessary (perhaps internally invoking {@code isReleasable}
     * before actually blocking). These actions are performed by any
     * thread invoking {@link ForkJoinPool#managedBlock(ManagedBlocker)}.
     * The unusual methods in this API accommodate synchronizers that
     * may, but don't usually, block for long periods. Similarly, they
     * allow more efficient internal handling of cases in which
     * additional workers may be, but usually are not, needed to
     * ensure sufficient parallelism.  Toward this end,
     * implementations of method {@code isReleasable} must be amenable
     * to repeated invocation.
     *
     * <p>For example, here is a ManagedBlocker based on a
     * ReentrantLock:
     * <pre> {@code
     * class ManagedLocker implements ManagedBlocker {
     *   final ReentrantLock lock;
     *   boolean hasLock = false;
     *   ManagedLocker(ReentrantLock lock) { this.lock = lock; }
     *   public boolean block() {
     *     if (!hasLock)
     *       lock.lock();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return hasLock || (hasLock = lock.tryLock());
     *   }
     * }}</pre>
     *
     * <p>Here is a class that possibly blocks waiting for an
     * item on a given queue:
     * <pre> {@code
     * class QueueTaker<E> implements ManagedBlocker {
     *   final BlockingQueue<E> queue;
     *   volatile E item = null;
     *   QueueTaker(BlockingQueue<E> q) { this.queue = q; }
     *   public boolean block() throws InterruptedException {
     *     if (item == null)
     *       item = queue.take();
     *     return true;
     *   }
     *   public boolean isReleasable() {
     *     return item != null || (item = queue.poll()) != null;
     *   }
     *   public E getItem() { // call after pool.managedBlock completes
     *     return item;
     *   }
     * }}</pre>
     */
    public static interface ManagedBlocker {
        /**
         * Possibly blocks the current thread, for example waiting for
         * a lock or condition.
         *
         * @return {@code true} if no additional blocking is necessary
         * (i.e., if isReleasable would return true)
         * @throws InterruptedException if interrupted while waiting
         * (the method is not required to do so, but is allowed to)
         */
        boolean block() throws InterruptedException;

        /**
         * Returns {@code true} if blocking is unnecessary.
         * @return {@code true} if blocking is unnecessary
         */
        boolean isReleasable();
    }

    /**
     * Runs the given possibly blocking task.  When {@linkplain
     * ForkJoinTask#inForkJoinPool() running in a ForkJoinPool}, this
     * method possibly arranges for a spare thread to be activated if
     * necessary to ensure sufficient parallelism while the current
     * thread is blocked in {@link ManagedBlocker#block blocker.block()}.
     *
     * <p>This method repeatedly calls {@code blocker.isReleasable()} and
     * {@code blocker.block()} until either method returns {@code true}.
     * Every call to {@code blocker.block()} is preceded by a call to
     * {@code blocker.isReleasable()} that returned {@code false}.
     *
     * <p>If not running in a ForkJoinPool, this method is
     * behaviorally equivalent to
     * <pre> {@code
     * while (!blocker.isReleasable())
     *   if (blocker.block())
     *     break;}</pre>
     *
     * If running in a ForkJoinPool, the pool may first be expanded to
     * ensure sufficient parallelism available during the call to
     * {@code blocker.block()}.
     *
     * @param blocker the blocker task
     * @throws InterruptedException if {@code blocker.block()} did so
     */
    public static void managedBlock(ManagedBlocker blocker)
        throws InterruptedException {
        if (blocker == null) throw new NullPointerException();
        ForkJoinPool p;
        ForkJoinWorkerThread wt;
        WorkQueue w;
        Thread t = Thread.currentThread();
        if ((t instanceof ForkJoinWorkerThread) &&
            (p = (wt = (ForkJoinWorkerThread)t).pool) != null &&
            (w = wt.workQueue) != null) {
            int block;
            while (!blocker.isReleasable()) {
                if ((block = p.tryCompensate(w)) != 0) {
                    try {
                        do {} while (!blocker.isReleasable() &&
                                     !blocker.block());
                    } finally {
                        CTL.getAndAdd(p, (block > 0) ? RC_UNIT : 0L);
                    }
                    break;
                }
            }
        }
        else {
            do {} while (!blocker.isReleasable() &&
                         !blocker.block());
        }
    }

    /**
     * If the given executor is a ForkJoinPool, poll and execute
     * AsynchronousCompletionTasks from worker's queue until none are
     * available or blocker is released.
     */
    static void helpAsyncBlocker(Executor e, ManagedBlocker blocker) {
        if (e instanceof ForkJoinPool) {
            WorkQueue w; ForkJoinWorkerThread wt; WorkQueue[] ws; int r, n;
            ForkJoinPool p = (ForkJoinPool)e;
            Thread thread = Thread.currentThread();
            if (thread instanceof ForkJoinWorkerThread &&
                (wt = (ForkJoinWorkerThread)thread).pool == p)
                w = wt.workQueue;
            else if ((r = ThreadLocalRandom.getProbe()) != 0 &&
                     (ws = p.workQueues) != null && (n = ws.length) > 0)
                w = ws[(n - 1) & r & SQMASK];
            else
                w = null;
            if (w != null)
                w.helpAsyncBlocker(blocker);
        }
    }

    // AbstractExecutorService overrides.  These rely on undocumented
    // fact that ForkJoinTask.adapt returns ForkJoinTasks that also
    // implement RunnableFuture.

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.AdaptedRunnable<T>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.AdaptedCallable<T>(callable);
    }

    // VarHandle mechanics
    private static final VarHandle CTL;
    private static final VarHandle MODE;
    static final VarHandle QA;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CTL = l.findVarHandle(ForkJoinPool.class, "ctl", long.class);
            MODE = l.findVarHandle(ForkJoinPool.class, "mode", int.class);
            QA = MethodHandles.arrayElementVarHandle(ForkJoinTask[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Reduce the risk of rare disastrous classloading in first call to
        // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
        Class<?> ensureLoaded = LockSupport.class;

        int commonMaxSpares = DEFAULT_COMMON_MAX_SPARES;
        try {
            String p = System.getProperty
                ("java.util.concurrent.ForkJoinPool.common.maximumSpares");
            if (p != null)
                commonMaxSpares = Integer.parseInt(p);
        } catch (Exception ignore) {}
        COMMON_MAX_SPARES = commonMaxSpares;

        defaultForkJoinWorkerThreadFactory =
            new DefaultForkJoinWorkerThreadFactory();
        modifyThreadPermission = new RuntimePermission("modifyThread");

        common = AccessController.doPrivileged(new PrivilegedAction<>() {
            public ForkJoinPool run() {
                return new ForkJoinPool((byte)0); }});

        COMMON_PARALLELISM = Math.max(common.mode & SMASK, 1);
    }

    /**
     * Factory for innocuous worker threads.
     */
    private static final class InnocuousForkJoinWorkerThreadFactory
        implements ForkJoinWorkerThreadFactory {

        /**
         * An ACC to restrict permissions for the factory itself.
         * The constructed workers have no permissions set.
         */
        private static final AccessControlContext ACC = contextWithPermissions(
            modifyThreadPermission,
            new RuntimePermission("enableContextClassLoaderOverride"),
            new RuntimePermission("modifyThreadGroup"),
            new RuntimePermission("getClassLoader"),
            new RuntimePermission("setContextClassLoader"));

        public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return AccessController.doPrivileged(
                new PrivilegedAction<>() {
                    public ForkJoinWorkerThread run() {
                        return new ForkJoinWorkerThread.
                            InnocuousForkJoinWorkerThread(pool); }},
                ACC);
        }
    }
}
