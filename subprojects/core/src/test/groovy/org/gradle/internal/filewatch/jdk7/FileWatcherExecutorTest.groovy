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
import org.gradle.api.tasks.util.PatternSet
import org.gradle.internal.filewatch.FileWatchListener
import org.gradle.internal.filewatch.FileWatcher
import spock.lang.Specification

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by lari on 13/04/15.
 */
class FileWatcherExecutorTest extends Specification {
    def fileWatcher
    def runningFlag
    FileWatchListener fileWatchListener
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

            @Override
            protected boolean quietPeriodBeforeNotifyingHasElapsed() {
                true
            }
        }
    }

    def "test FileWatcherExecutor interaction with WatchService"() {
        given:
        def file = new File("a/b/c")
        files << file
        def filePathMock = Mock(Path)
        dirToPathMocks.put(file.getParentFile(), filePathMock)
        def fileWatchKey = Mock(WatchKey)

        def dir = new File("a2/b2")
        def directoryTree = Mock(DirectoryTree)
        directoryTree.getDir() >> dir
        directoryTree.getPatterns() >> new PatternSet()
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
        fileSystemProvider.readAttributes(dirPathMock, _, _) >> dirAttributes

        def subDirPathMock = Mock(Path)
        subDirPathMock.getFileSystem() >> fileSystem

        def directoryStream = Mock(DirectoryStream)
        fileSystemProvider.newDirectoryStream(dirPathMock, Files.AcceptAllFilter.FILTER) >> directoryStream
        directoryStream.iterator() >> [subDirPathMock].iterator()

        def subDirStream = Mock(DirectoryStream)
        fileSystemProvider.newDirectoryStream(subDirPathMock, Files.AcceptAllFilter.FILTER) >> subDirStream
        subDirStream.iterator() >> Collections.emptyIterator()

        def subDirAttributes  = Mock(BasicFileAttributes)
        subDirAttributes.isDirectory() >> true
        fileSystemProvider.readAttributes(subDirPathMock, _, _) >> subDirAttributes

        def subDirWatchKey = Mock(WatchKey)

        maxWatchLoops = 1

        def mockWatchEvent = Mock(WatchEvent)
        def modifiedFilePath = Mock(Path)

        when:
        fileWatcherExecutor.run()
        then: 'watches get registered'
        filePathMock.register(watchService, FileWatcherExecutor.WATCH_KINDS, FileWatcherExecutor.WATCH_MODIFIERS) >> fileWatchKey
        1 * dirPathMock.register(watchService, FileWatcherExecutor.WATCH_KINDS, FileWatcherExecutor.WATCH_MODIFIERS) >> dirWatchKey
        1 * subDirPathMock.register(watchService, FileWatcherExecutor.WATCH_KINDS, FileWatcherExecutor.WATCH_MODIFIERS) >> subDirWatchKey

        then: 'watchservice gets polled and returns a modification in a directory'
        watchService.poll(_, _) >> dirWatchKey

        1 * dirWatchKey.watchable() >> dirPathMock
        1 * dirWatchKey.pollEvents() >> [mockWatchEvent]
        1 * dirPathMock.toFile() >> dir

        mockWatchEvent.kind() >> StandardWatchEventKinds.ENTRY_MODIFY
        mockWatchEvent.context() >> modifiedFilePath
        modifiedFilePath.getParent() >> dirPathMock
        modifiedFilePath.getName() >> 'modifiedfile'

        then: 'relative path gets resolved'
        1 * dirPathMock.resolve(modifiedFilePath) >> { Path other -> other }
        1 * modifiedFilePath.toFile() >> new File(dir, "modifiedfile")

        then: 'WatchKey gets resetted'
        1 * dirWatchKey.reset()
        then: 'listener gets called'
        1 * fileWatchListener.changesDetected(_)
        then: 'finally watchservice gets closed'
        watchService.close()
    }
}
