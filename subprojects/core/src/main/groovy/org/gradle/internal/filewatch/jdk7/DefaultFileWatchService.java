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

import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.filewatch.FileWatchService;
import org.gradle.internal.filewatch.FileWatcher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by lari on 13/04/15.
 */
public class DefaultFileWatchService implements FileWatchService, Stoppable {
    private ExecutorService executor = createExecutor();

    protected ExecutorService createExecutor() {
        return Executors.unconfigurableExecutorService(Executors.newCachedThreadPool(new ThreadFactory() {
            private final String namePrefix = "filewatcher-thread-";
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, namePrefix + threadNumber.getAndIncrement());
            }
        }));
    }

    @Override
    public FileWatcher createFileWatcher() {
        return new DefaultFileWatcher(executor);
    }

    @Override
    public void stop() {
        executor.shutdown();
    }
}
