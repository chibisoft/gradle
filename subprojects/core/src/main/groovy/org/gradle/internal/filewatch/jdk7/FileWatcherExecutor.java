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
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.DefaultFileTreeElement;
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
    private final WatchEvent.Modifier[] watchModifiers;
    private long lastEventReceivedMillis;
    private boolean pendingNotification;
    private Map<Path, Set<File>> individualFilesByParentPath;
    private Map<Path, DirectoryTree> pathToDirectoryTree;

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

    protected void watchLoop(WatchService watchService) throws InterruptedException {
        pendingNotification = false;
        while (watchLoopRunning()) {
            WatchKey watchKey = watchService.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (watchKey != null) {
                lastEventReceivedMillis = System.currentTimeMillis();
                handleWatchKey(watchService, watchKey);
            }
            handleNotifyChanges();
        }
    }

    protected void handleWatchKey(WatchService watchService, WatchKey watchKey) {
        Path watchedPath = (Path)watchKey.watchable();

        RelativePath parentPath = toRelativePath(watchedPath.toFile(), watchedPath, null);
        DirectoryTree watchedTree = pathToDirectoryTree.get(watchedPath);
        Set<File> individualFiles = individualFilesByParentPath.get(watchedPath);

        for (WatchEvent<?> event : watchKey.pollEvents()) {
            WatchEvent.Kind kind = event.kind();

            if (kind == OVERFLOW) {
                // overflow event occurs when some change event might have been lost
                // notify changes in that case
                pendingNotification = true;
                continue;
            }

            if (kind.type() == Path.class) {
                WatchEvent<Path> ev = (WatchEvent<Path>) (event);
                Path relativePath = ev.context();
                Path fullPath = watchedPath.resolve(relativePath);

                if(watchedTree != null) {
                    FileTreeElement fileTreeElement = toFileTreeElement(fullPath, relativePath, parentPath);
                    if(!watchedTree.getPatterns().getAsExcludeSpec().isSatisfiedBy(fileTreeElement)) {
                        if (kind == ENTRY_CREATE) {
                            if (Files.isDirectory(fullPath, NOFOLLOW_LINKS) && !supportsWatchingSubTree()) {
                                try {
                                    registerSubTree(watchService, fullPath);
                                } catch (IOException e) {
                                    // ignore
                                }
                            }
                        }
                        if(watchedTree.getPatterns().getAsIncludeSpec().isSatisfiedBy(fileTreeElement)) {
                            pendingNotification = true;
                        }
                    }
                } else if (individualFiles != null) {
                    File fullFile = fullPath.toFile().getAbsoluteFile();
                    if(individualFiles.contains(fullFile)) {
                        pendingNotification = true;
                    }
                } else {
                    System.err.println("unmapped path " + fullPath.toString());
                }
            }
        }

        watchKey.reset();
    }

    private RelativePath toRelativePath(File file, Path path, RelativePath parentPath) {
        return RelativePath.parse(!file.isDirectory(), parentPath, path.toString());
    }

    private FileTreeElement toFileTreeElement(Path fullPath, Path relativePath, RelativePath parentPath) {
        File file = fullPath.toFile();
        return new FileTreeElement(file, toRelativePath(file, relativePath, parentPath));
    }

    protected void handleNotifyChanges() {
        if (pendingNotification && quietPeriodBeforeNotifyingHasElapsed()) {
            notifyChanged();
            pendingNotification = false;
        }
    }

    protected boolean quietPeriodBeforeNotifyingHasElapsed() {
        return QUIET_PERIOD_MILLIS <= 0 || System.currentTimeMillis() - lastEventReceivedMillis > QUIET_PERIOD_MILLIS;
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
            children.add(file.getAbsoluteFile());
        }
        for (Path parent : individualFilesByParentPath.keySet()) {
            registerSinglePathNoSubtree(watchService, parent);
        }
    }

    protected Path dirToPath(File dir) {
        return dir.getAbsoluteFile().toPath();
    }

    private void registerDirTreeInputs(WatchService watchService) throws IOException {
        pathToDirectoryTree = new HashMap<Path, DirectoryTree>();
        for (DirectoryTree tree : directoryTrees) {
            Path path = dirToPath(tree.getDir());
            pathToDirectoryTree.put(path, tree);
            registerSubTree(watchService, path);
        }
    }

    private void registerSubTree(final WatchService watchService, Path path) throws IOException {
        if (supportsWatchingSubTree()) {
            registerSinglePath(watchService, path);
        } else {
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
    }

    protected WatchService createWatchService() {
        try {
            return FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("IOException in creating WatchService", e);
        }
    }

    private static class FileTreeElement extends DefaultFileTreeElement {
        public FileTreeElement(File file, RelativePath relativePath) {
            super(file, relativePath, null, null);
        }
    }
}
