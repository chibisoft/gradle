/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.nativeplatform.internal.services;

import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.PluginServiceRegistry;
import org.gradle.nativeplatform.internal.NativeExecutableBinaryRenderer;
import org.gradle.nativeplatform.internal.SharedLibraryBinaryRenderer;
import org.gradle.nativeplatform.internal.StaticLibraryBinaryRenderer;
import org.gradle.nativeplatform.internal.resolve.NativeDependencyResolverServices;
import org.gradle.nativeplatform.test.internal.NativeTestSuiteBinaryRenderer;
import org.gradle.nativeplatform.toolchain.internal.gcc.version.CompilerMetaDataProviderFactory;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.DefaultVisualStudioLocator;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.DefaultWindowsSdkLocator;

public class NativeBinaryServices implements PluginServiceRegistry {
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.add(SharedLibraryBinaryRenderer.class);
        registration.add(StaticLibraryBinaryRenderer.class);
        registration.add(NativeExecutableBinaryRenderer.class);
        registration.add(NativeTestSuiteBinaryRenderer.class);
    }

    public void registerBuildServices(ServiceRegistration registration) {
        registration.add(DefaultVisualStudioLocator.class);
        registration.add(DefaultWindowsSdkLocator.class);
        registration.add(CompilerMetaDataProviderFactory.class);
    }

    public void registerGradleServices(ServiceRegistration registration) {
    }

    public void registerProjectServices(ServiceRegistration registration) {
        registration.addProvider(new NativeDependencyResolverServices());
    }
}
