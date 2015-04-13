package org.gradle.internal.filewatch.jdk7;

import org.gradle.internal.filewatch.FileWatchInputs;
import org.gradle.internal.filewatch.FileWatchListener;
import org.gradle.internal.filewatch.FileWatcher;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation for {@link FileWatcher}
 */
public class DefaultFileWatcher implements FileWatcher {
    private final ExecutorService executor;
    private AtomicBoolean runningFlag = new AtomicBoolean(false);
    private Future<?> execution;
    private final int STOP_TIMEOUT_SECONDS = 10;

    public DefaultFileWatcher(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public synchronized void watch(FileWatchInputs inputs, FileWatchListener listener) {
        if(runningFlag.get()) {
            throw new IllegalStateException("FileWatcher cannot start watching new inputs when it's already running.");
        }
        runningFlag.set(true);
        execution = executor.submit(new FileWatcherExecutor(this, runningFlag, listener, new ArrayList(inputs.getDirectoryTrees()), new ArrayList(inputs.getFiles())));
    }

    @Override
    public synchronized void stop() {
        if(runningFlag.get()) {
            runningFlag.set(false);
            waitForStop();
        }
    }

    private void waitForStop() {
        try {
            execution.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        } catch (ExecutionException e) {
            // ignore
        } catch (TimeoutException e) {
            throw new RuntimeException("Running FileWatcher wasn't stopped in timeout limits.", e);
        } finally {
            execution = null;
        }
    }
}
