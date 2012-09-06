/*
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.gatk.executive;

import net.sf.picard.reference.IndexedFastaSequenceFile;
import org.apache.log4j.Logger;
import org.broadinstitute.sting.gatk.GenomeAnalysisEngine;
import org.broadinstitute.sting.gatk.datasources.reads.SAMDataSource;
import org.broadinstitute.sting.gatk.datasources.reads.Shard;
import org.broadinstitute.sting.gatk.datasources.rmd.ReferenceOrderedDataSource;
import org.broadinstitute.sting.gatk.io.OutputTracker;
import org.broadinstitute.sting.gatk.iterators.NullSAMIterator;
import org.broadinstitute.sting.gatk.iterators.StingSAMIterator;
import org.broadinstitute.sting.gatk.resourcemanagement.ThreadAllocation;
import org.broadinstitute.sting.gatk.traversals.*;
import org.broadinstitute.sting.gatk.walkers.*;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.exceptions.UserException;
import org.broadinstitute.sting.utils.threading.ThreadEfficiencyMonitor;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Collection;


/**
 * Created by IntelliJ IDEA.
 * User: mhanna
 * Date: Apr 26, 2009
 * Time: 12:37:23 PM
 *
 * General base class for all scheduling algorithms
 */

/** Shards and schedules data in manageable chunks. */
public abstract class MicroScheduler implements MicroSchedulerMBean {
    // TODO -- remove me and retire non nano scheduled versions of traversals
    private final static boolean USE_NANOSCHEDULER_FOR_EVERYTHING = true;
    protected static final Logger logger = Logger.getLogger(MicroScheduler.class);

    /**
     * Counts the number of instances of the class that are currently alive.
     */
    private static int instanceNumber = 0;

    /**
     * The engine invoking this scheduler.
     */
    protected final GenomeAnalysisEngine engine;

    protected final TraversalEngine traversalEngine;
    protected final IndexedFastaSequenceFile reference;

    private final SAMDataSource reads;
    protected final Collection<ReferenceOrderedDataSource> rods;

    private final MBeanServer mBeanServer;
    private final ObjectName mBeanName;

    /**
     * Threading efficiency monitor for tracking the resource utilization of the GATK
     *
     * may be null
     */
    ThreadEfficiencyMonitor threadEfficiencyMonitor = null;

    /**
     * MicroScheduler factory function.  Create a microscheduler appropriate for reducing the
     * selected walker.
     *
     * @param walker        Which walker to use.
     * @param reads         the informations associated with the reads
     * @param reference     the reference file
     * @param rods          the rods to include in the traversal
     * @param threadAllocation Number of threads to utilize.
     *
     * @return The best-fit microscheduler.
     */
    public static MicroScheduler create(GenomeAnalysisEngine engine, Walker walker, SAMDataSource reads, IndexedFastaSequenceFile reference, Collection<ReferenceOrderedDataSource> rods, ThreadAllocation threadAllocation) {
        if ( threadAllocation.isRunningInParallelMode() )
            logger.info(String.format("Running the GATK in parallel mode with %d CPU thread(s) for each of %d data thread(s)",
                    threadAllocation.getNumCPUThreadsPerDataThread(), threadAllocation.getNumDataThreads()));

        if ( threadAllocation.getNumDataThreads() > 1 ) {
            if (walker.isReduceByInterval())
                throw new UserException.BadArgumentValue("nt", String.format("The analysis %s aggregates results by interval.  Due to a current limitation of the GATK, analyses of this type do not currently support parallel execution.  Please run your analysis without the -nt option.", engine.getWalkerName(walker.getClass())));

            if ( ! (walker instanceof TreeReducible) ) {
                throw badNT("nt", engine, walker);
            } else {
                return new HierarchicalMicroScheduler(engine, walker, reads, reference, rods, threadAllocation);
            }
        } else {
            if ( threadAllocation.getNumCPUThreadsPerDataThread() > 1 && ! (walker instanceof NanoSchedulable) )
                throw badNT("nct", engine, walker);
            return new LinearMicroScheduler(engine, walker, reads, reference, rods, threadAllocation);
        }
    }

    private static UserException badNT(final String parallelArg, final GenomeAnalysisEngine engine, final Walker walker) {
        throw new UserException.BadArgumentValue("nt",
                String.format("The analysis %s currently does not support parallel execution with %s.  " +
                        "Please run your analysis without the %s option.", engine.getWalkerName(walker.getClass()), parallelArg, parallelArg));
    }

    /**
     * Create a microscheduler given the reads and reference.
     *
     * @param walker  the walker to execute with
     * @param reads   The reads.
     * @param reference The reference.
     * @param rods    the rods to include in the traversal
     * @param threadAllocation the allocation of threads to use in the underlying traversal
     */
    protected MicroScheduler(final GenomeAnalysisEngine engine,
                             final Walker walker,
                             final SAMDataSource reads,
                             final IndexedFastaSequenceFile reference,
                             final Collection<ReferenceOrderedDataSource> rods,
                             final ThreadAllocation threadAllocation) {
        this.engine = engine;
        this.reads = reads;
        this.reference = reference;
        this.rods = rods;

        if (walker instanceof ReadWalker) {
            traversalEngine = USE_NANOSCHEDULER_FOR_EVERYTHING || threadAllocation.getNumCPUThreadsPerDataThread() > 1
                    ? new TraverseReadsNano(threadAllocation.getNumCPUThreadsPerDataThread())
                    : new TraverseReads();
        } else if (walker instanceof LocusWalker) {
            traversalEngine = USE_NANOSCHEDULER_FOR_EVERYTHING || threadAllocation.getNumCPUThreadsPerDataThread() > 1
                    ? new TraverseLociNano(threadAllocation.getNumCPUThreadsPerDataThread())
                    : new TraverseLociLinear();
        } else if (walker instanceof DuplicateWalker) {
            traversalEngine = new TraverseDuplicates();
        } else if (walker instanceof ReadPairWalker) {
            traversalEngine = new TraverseReadPairs();
        } else if (walker instanceof ActiveRegionWalker) {
            traversalEngine = new TraverseActiveRegions();
        } else {
            throw new UnsupportedOperationException("Unable to determine traversal type, the walker is an unknown type.");
        }        

        traversalEngine.initialize(engine);

        // JMX does not allow multiple instances with the same ObjectName to be registered with the same platform MXBean.
        // To get around this limitation and since we have no job identifier at this point, register a simple counter that
        // will count the number of instances of this object that have been created in this JVM.
        int thisInstance = instanceNumber++;
        mBeanServer = ManagementFactory.getPlatformMBeanServer();
        try {
            mBeanName = new ObjectName("org.broadinstitute.sting.gatk.executive:type=MicroScheduler,instanceNumber="+thisInstance);
            mBeanServer.registerMBean(this, mBeanName);
        }
        catch (JMException ex) {
            throw new ReviewedStingException("Unable to register microscheduler with JMX", ex);
        }
    }

    /**
     * Return the ThreadEfficiencyMonitor we are using to track our resource utilization, if there is one
     *
     * @return the monitor, or null if none is active
     */
    public ThreadEfficiencyMonitor getThreadEfficiencyMonitor() {
        return threadEfficiencyMonitor;
    }

    /**
     * Inform this Microscheduler to use the efficiency monitor used to create threads in subclasses
     *
     * @param threadEfficiencyMonitor
     */
    public void setThreadEfficiencyMonitor(final ThreadEfficiencyMonitor threadEfficiencyMonitor) {
        this.threadEfficiencyMonitor = threadEfficiencyMonitor;
    }

    /**
     * Walks a walker over the given list of intervals.
     *
     * @param walker        Computation to perform over dataset.
     * @param shardStrategy A strategy for sharding the data.
     *
     * @return the return type of the walker
     */
    public abstract Object execute(Walker walker, Iterable<Shard> shardStrategy);

    /**
     * Retrieves the object responsible for tracking and managing output.
     * @return An output tracker, for loading data in and extracting results.  Will not be null.
     */
    public abstract OutputTracker getOutputTracker();

    /**
     * Gets the an iterator over the given reads, which will iterate over the reads in the given shard.
     * @param shard the shard to use when querying reads.
     * @return an iterator over the reads specified in the shard.
     */
    protected StingSAMIterator getReadIterator(Shard shard) {
        return (!reads.isEmpty()) ? reads.seek(shard) : new NullSAMIterator();
    }

    /**
     * Print summary information for the analysis.
     * @param sum The final reduce output.
     */
    protected void printOnTraversalDone(Object sum) {
        traversalEngine.printOnTraversalDone();
    }

    /**
     * Must be called by subclasses when execute is done
     */
    protected void executionIsDone() {
        // Print out the threading efficiency of this HMS, if state monitoring is enabled
        if ( threadEfficiencyMonitor != null ) {
            // include the master thread information
            threadEfficiencyMonitor.threadIsDone(Thread.currentThread());
            threadEfficiencyMonitor.printUsageInformation(logger);
        }
    }

    /**
     * Gets the engine that created this microscheduler.
     * @return The engine owning this microscheduler.
     */
    public GenomeAnalysisEngine getEngine() { return engine; }

    /**
     * Returns data source maintained by this scheduler
     * @return
     */
    public SAMDataSource getSAMDataSource() { return reads; }

    /**
     * Returns the reference maintained by this scheduler.
     * @return The reference maintained by this scheduler.
     */
    public IndexedFastaSequenceFile getReference() { return reference; }

    /**
     * Gets the filename to which performance data is currently being written.
     * @return Filename to which performance data is currently being written.
     */
    public String getPerformanceLogFileName() {
        return traversalEngine.getPerformanceLogFileName();
    }

    /**
     * Set the filename of the log for performance.  If set,
     * @param fileName filename to use when writing performance data.
     */
    public void setPerformanceLogFileName(String fileName) {
        traversalEngine.setPerformanceLogFileName(fileName);
    }

    /**
     * Gets the frequency with which performance data is written.
     * @return Frequency, in seconds, of performance log writes.
     */
    public long getPerformanceProgressPrintFrequencySeconds() {
        return traversalEngine.getPerformanceProgressPrintFrequencySeconds();
    }    

    /**
     * How often should the performance log message be written?
     * @param seconds number of seconds between messages indicating performance frequency.
     */
    public void setPerformanceProgressPrintFrequencySeconds(long seconds) {
        traversalEngine.setPerformanceProgressPrintFrequencySeconds(seconds);
    }

    protected void cleanup() {
        try {
            mBeanServer.unregisterMBean(mBeanName);
        }
        catch (JMException ex) {
            throw new ReviewedStingException("Unable to unregister microscheduler with JMX", ex);
        }
    }
}
