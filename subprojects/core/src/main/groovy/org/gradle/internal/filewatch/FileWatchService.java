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

package org.gradle.internal.filewatch;

import org.gradle.api.file.DirectoryTree;
import org.gradle.internal.concurrent.Stoppable;

import java.io.File;

/**
 * Service for watching changes on multiple {@link DirectoryTree} or individual {@link File}s
 *
 * Designed to be used with a single set of inputs and a single listener.
 *
 */
public interface FileWatchService extends Stoppable {
    /**
     * Sets the directories and files to watch for changes
     *
     * Removes any previously assigned definition.
     *
     * @param inputs
     * @see FileWatchInputs
     */
    void setWatchInputs(FileWatchInputs inputs);

    /**
     * Sets the single listener for changes.
     *
     * Removes any previously assigned listener.
     *
     * @param listener
     */
    void setListener(FileWatchListener listener);

    /**
     * Starts watching for file changes on a separate background thread.
     */
    void start();
}
