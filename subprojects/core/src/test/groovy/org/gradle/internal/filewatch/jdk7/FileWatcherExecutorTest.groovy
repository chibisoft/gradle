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

package org.gradle.internal.filewatch.jdk7

import org.gradle.api.file.DirectoryTree
import org.gradle.api.internal.file.collections.DirectoryFileTree
import org.gradle.internal.filewatch.FileWatchListener
import org.gradle.internal.filewatch.FileWatcher
import spock.lang.Specification

import java.nio.file.DirectoryStream
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by lari on 13/04/15.
 */
class FileWatcherExecutorTest extends Specification {
    def fileWatcher
    def runningFlag
    def fileWatchListener
    def directories
    def files
    def watchService
    def fileWatcherExecutor
    int maxWatchLoops = 0
    Map<File, Path> dirToPathMocks = [:]

    def setup() {
        fileWatcher = Mock(FileWatcher)
        runningFlag = new AtomicBoolean(true)
        fileWatchListener = Mock(FileWatchListener)
        directories = []
        files = []
        watchService = Mock(WatchService)
        fileWatcherExecutor = new FileWatcherExecutor(fileWatcher, runningFlag, fileWatchListener, directories, files) {
            int watchLoopCounter = 0

            @Override
            protected boolean supportsWatchingSubTree() {
                return false
            }

            @Override
            protected WatchService createWatchService() {
                return watchService
            }

            @Override
            protected Path dirToPath(File dir) {
                Path p = dirToPathMocks.get(dir)
                if(p == null) {
                    p = Mock(Path)
                    dirToPathMocks.put(dir, p)
                }
                p
            }

            @Override
            protected boolean watchLoopRunning() {
                watchLoopCounter++ < maxWatchLoops
            }
        }
    }

    def "test registering inputs"() {
        given:
        def file = new File("a/b/c")
        files << file
        def filePathMock = Mock(Path)
        dirToPathMocks.put(file.getParentFile(), filePathMock)
        def fileWatchKey = Mock(WatchKey)

        def dir = new File("a2/b2")
        def directoryTree = Mock(DirectoryTree)
        directoryTree.getDir() >> {
            dir
        }
        directories << directoryTree
        def dirPathMock = Mock(Path)
        def fileSystemProvider = Mock(FileSystemProvider)
        def fileSystem = Mock(FileSystem)
        fileSystem.provider() >> fileSystemProvider
        dirPathMock.getFileSystem() >> fileSystem
        dirToPathMocks.put(dir, dirPathMock)
        def dirWatchKey = Mock(WatchKey)
        def dirAttributes  = Mock(BasicFileAttributes)
        dirAttributes.isDirectory() >> true
        def directoryStream = Mock(DirectoryStream)
        fileSystemProvider.newDirectoryStream(dirPathMock, Files.AcceptAllFilter.FILTER) >> directoryStream
        directoryStream.iterator() >> Collections.emptyIterator()

        when:
        fileWatcherExecutor.run()
        then:
        filePathMock.register(watchService, FileWatcherExecutor.WATCH_KINDS, FileWatcherExecutor.WATCH_MODIFIERS) >> {
            fileWatchKey
        }
        1 * dirPathMock.register(watchService, FileWatcherExecutor.WATCH_KINDS, FileWatcherExecutor.WATCH_MODIFIERS) >> {
            dirWatchKey
        }
        fileSystemProvider.readAttributes(dirPathMock, _, _) >> {
            dirAttributes
        }
        watchService.close()
    }
}
