package org.gradle.internal.filewatch.jdk7;

import com.sun.nio.file.ExtendedWatchEventModifier;
import com.sun.nio.file.SensitivityWatchEventModifier;
import org.gradle.api.file.DirectoryTree;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.filewatch.FileWatchEvent;
import org.gradle.internal.filewatch.FileWatchInputs;
import org.gradle.internal.filewatch.FileWatchListener;
import org.gradle.internal.filewatch.FileWatcher;
import org.gradle.internal.os.OperatingSystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;

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
            throw new IllegalArgumentException("FileWatcher cannot start watching new inputs when it's already running.");
        }
        runningFlag.set(true);
        execution = executor.submit(new FileWatcherExecutor(this, runningFlag, listener, new ArrayList(inputs.getDirectoryTrees()), new ArrayList(inputs.getFiles()), new ArrayList(inputs.getFilters()) ));
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

    private static class FileWatcherExecutor implements Runnable {
        // http://stackoverflow.com/a/18362404
        // make watch sensitivity as 2 seconds on MacOSX, polls every 2 seconds for changes. Default is 10 seconds.
        private static final WatchEvent.Modifier[] WATCH_MODIFIERS = new WatchEvent.Modifier[]{SensitivityWatchEventModifier.HIGH};
        private static final WatchEvent.Kind[] WATCH_KINDS = new WatchEvent.Kind[]{StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY};
        private static final long QUIET_PERIOD_MILLIS = 1000L;
        private static final int POLL_TIMEOUT_MILLIS = 250;
        private final FileWatcher fileWatcher;
        private final AtomicBoolean runningFlag;
        private final Collection<DirectoryTree> directoryTrees;
        private final Collection<File> files;
        private final Collection<PatternSet> filters;
        private final FileWatchListener listener;
        private Map<WatchKey,Path> watchKeys;
        private long lastEventReceivedMillis;
        private WatchEvent.Modifier[] watchModifiers;
        private Map<Path, Set<File>> individualFilesByParentPath;

        public FileWatcherExecutor(FileWatcher fileWatcher, AtomicBoolean runningFlag, FileWatchListener listener, Collection<DirectoryTree> directoryTrees, Collection<File> files, Collection<PatternSet> filters) {
            this.fileWatcher = fileWatcher;
            this.runningFlag = runningFlag;
            this.listener = listener;
            this.directoryTrees = directoryTrees;
            this.files = files;
            this.filters = filters;
            this.watchModifiers = createWatchModifiers();
        }

        private WatchEvent.Modifier[] createWatchModifiers() {
            if(supportsWatchingSubTree()) {
                WatchEvent.Modifier[] modifiers = Arrays.copyOf(WATCH_MODIFIERS, WATCH_MODIFIERS.length+1);
                modifiers[modifiers.length-1] = ExtendedWatchEventModifier.FILE_TREE;
                return modifiers;
            } else {
                return WATCH_MODIFIERS;
            }
        }

        private boolean supportsWatchingSubTree() {
            return OperatingSystem.current().isWindows();
        }

        @Override
        public void run() {
            WatchService watchService = createWatchService();
            try {
                try {
                    registerInputs(watchService);
                } catch (IOException e) {
                    throw new RuntimeException("IOException in registering watch inputs", e);
                }
                try {
                    watchLoop(watchService);
                } catch (InterruptedException e) {
                    // ignore
                }
            } finally {
                try {
                    watchService.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        private void watchLoop(WatchService watchService) throws InterruptedException {
            boolean foundChanges = false;
            while (runningFlag.get() && !Thread.currentThread().isInterrupted()) {
                if(foundChanges && (QUIET_PERIOD_MILLIS <= 0 || System.currentTimeMillis() - lastEventReceivedMillis > QUIET_PERIOD_MILLIS)) {
                    notifyChanged();
                    foundChanges = false;
                }
                WatchKey watchKey = watchService.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (watchKey != null) {
                    lastEventReceivedMillis = System.currentTimeMillis();

                    Path dir = watchKeys.get(watchKey);

                    if(dir == null) {
                        watchKey.cancel();
                        continue;
                    }

                    for (WatchEvent<?> event : watchKey.pollEvents()) {
                        WatchEvent.Kind kind = event.kind();

                        if (kind == OVERFLOW) {
                            // overflow event occurs when some change event might have been lost
                            // notify changes in that case
                            foundChanges = true;
                            continue;
                        }

                        if(kind.type() == Path.class) {
                            WatchEvent<Path> ev = (WatchEvent<Path>) (event);
                            Path path = ev.context();

                            if (kind == ENTRY_CREATE) {
                                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                                    try {
                                        registerSubTree(watchService, path);
                                    } catch (IOException e) {
                                        // ignore
                                    }
                                }
                            } else if (kind == ENTRY_DELETE) {
                                if (path.equals(dir)) {
                                    watchKey.cancel();
                                    watchKeys.remove(watchKey);
                                }
                            }
                        }
                    }

                    if(!watchKey.reset()) {
                        watchKeys.remove(watchKey);
                    }
                }
            }
        }

        private void notifyChanged() {
            listener.changesDetected(new FileWatchEvent() {
                @Override
                public FileWatcher getSource() {
                    return fileWatcher;
                }
            });
        }

        private void registerInputs(WatchService watchService) throws IOException {
            registerDirTreeInputs(watchService);
            registerIndividualFileInputs(watchService);
        }

        private void registerIndividualFileInputs(WatchService watchService) throws IOException {
            individualFilesByParentPath = new HashMap<Path, Set<File>>();
            for(File file : files) {
                Path parent = file.getParentFile().getAbsoluteFile().toPath();
                Set<File> children = individualFilesByParentPath.get(parent);
                if(children == null) {
                    children = new LinkedHashSet<File>();
                    individualFilesByParentPath.put(parent, children);
                }
                children.add(file);
            }
            for(Path parent : individualFilesByParentPath.keySet()) {
                registerSinglePathNoSubtree(watchService, parent);
            }
        }

        private void registerDirTreeInputs(WatchService watchService) throws IOException {
            for(DirectoryTree tree : directoryTrees) {
                registerSubTree(watchService, tree.getDir().getAbsoluteFile().toPath());
            }
        }

        private void registerSubTree(final WatchService watchService, Path path) throws IOException {
            registerSinglePath(watchService, path);
            if (!supportsWatchingSubTree()) {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        registerSinglePathNoSubtree(watchService, dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }

        private void registerSinglePath(WatchService watchService, Path path) throws IOException {
            registerSinglePathWithModifier(watchService, path, watchModifiers);
        }

        private void registerSinglePathNoSubtree(WatchService watchService, Path path) throws IOException {
            registerSinglePathWithModifier(watchService, path, WATCH_MODIFIERS);
        }

        private void registerSinglePathWithModifier(WatchService watchService, Path path, WatchEvent.Modifier[] watchModifiers) throws IOException {
            WatchKey key = path.register(watchService, WATCH_KINDS, watchModifiers);
            watchKeys.put(key, path);
        }

        private WatchService createWatchService() {
            try {
                return FileSystems.getDefault().newWatchService();
            } catch (IOException e) {
                throw new RuntimeException("IOException in creating WatchService", e);
            }
        }
    }
}
