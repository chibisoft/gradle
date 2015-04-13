/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.filewatch.jdk7;

import com.sun.nio.file.ExtendedWatchEventModifier;
import com.sun.nio.file.SensitivityWatchEventModifier;
import org.gradle.api.file.DirectoryTree;
import org.gradle.internal.filewatch.FileWatchEvent;
import org.gradle.internal.filewatch.FileWatchListener;
import org.gradle.internal.filewatch.FileWatcher;
import org.gradle.internal.os.OperatingSystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Created by lari on 13/04/15.
 */
class FileWatcherExecutor implements Runnable {
    // http://stackoverflow.com/a/18362404
    // make watch sensitivity as 2 seconds on MacOSX, polls every 2 seconds for changes. Default is 10 seconds.
    static final WatchEvent.Modifier[] WATCH_MODIFIERS = new WatchEvent.Modifier[]{SensitivityWatchEventModifier.HIGH};
    static final WatchEvent.Kind[] WATCH_KINDS = new WatchEvent.Kind[]{StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY};
    static final long QUIET_PERIOD_MILLIS = 1000L;
    static final int POLL_TIMEOUT_MILLIS = 250;
    private final FileWatcher fileWatcher;
    private final AtomicBoolean runningFlag;
    private final Collection<DirectoryTree> directoryTrees;
    private final Collection<File> files;
    private final FileWatchListener listener;
    private Map<WatchKey, Path> watchKeys;
    private long lastEventReceivedMillis;
    private WatchEvent.Modifier[] watchModifiers;
    private Map<Path, Set<File>> individualFilesByParentPath;

    public FileWatcherExecutor(FileWatcher fileWatcher, AtomicBoolean runningFlag, FileWatchListener listener, Collection<DirectoryTree> directoryTrees, Collection<File> files) {
        this.fileWatcher = fileWatcher;
        this.runningFlag = runningFlag;
        this.listener = listener;
        this.directoryTrees = directoryTrees;
        this.files = files;
        this.watchModifiers = createWatchModifiers();
    }

    private WatchEvent.Modifier[] createWatchModifiers() {
        if (supportsWatchingSubTree()) {
            WatchEvent.Modifier[] modifiers = Arrays.copyOf(WATCH_MODIFIERS, WATCH_MODIFIERS.length + 1);
            modifiers[modifiers.length - 1] = ExtendedWatchEventModifier.FILE_TREE;
            return modifiers;
        } else {
            return WATCH_MODIFIERS;
        }
    }

    protected boolean supportsWatchingSubTree() {
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
        while (watchLoopRunning()) {
            if (foundChanges && (QUIET_PERIOD_MILLIS <= 0 || System.currentTimeMillis() - lastEventReceivedMillis > QUIET_PERIOD_MILLIS)) {
                notifyChanged();
                foundChanges = false;
            }
            WatchKey watchKey = watchService.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (watchKey != null) {
                lastEventReceivedMillis = System.currentTimeMillis();

                Path dir = watchKeys.get(watchKey);

                if (dir == null) {
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

                    if (kind.type() == Path.class) {
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

                if (!watchKey.reset()) {
                    watchKeys.remove(watchKey);
                }
            }
        }
    }

    protected boolean watchLoopRunning() {
        return runningFlag.get() && !Thread.currentThread().isInterrupted();
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
        watchKeys = new HashMap<WatchKey, Path>();
        registerDirTreeInputs(watchService);
        registerIndividualFileInputs(watchService);
    }

    private void registerIndividualFileInputs(WatchService watchService) throws IOException {
        individualFilesByParentPath = new HashMap<Path, Set<File>>();
        for (File file : files) {
            Path parent = dirToPath(file.getParentFile());
            Set<File> children = individualFilesByParentPath.get(parent);
            if (children == null) {
                children = new LinkedHashSet<File>();
                individualFilesByParentPath.put(parent, children);
            }
            children.add(file);
        }
        for (Path parent : individualFilesByParentPath.keySet()) {
            registerSinglePathNoSubtree(watchService, parent);
        }
    }

    protected Path dirToPath(File dir) {
        return dir.getAbsoluteFile().toPath();
    }

    private void registerDirTreeInputs(WatchService watchService) throws IOException {
        for (DirectoryTree tree : directoryTrees) {
            registerSubTree(watchService, dirToPath(tree.getDir()));
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

    protected WatchService createWatchService() {
        try {
            return FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("IOException in creating WatchService", e);
        }
    }
}
