// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.model.serialization.jarRepository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension;
import org.jetbrains.jps.model.serialization.JpsProjectExtensionSerializer;

import java.util.Collections;
import java.util.List;

/**
 * @author Eugene Zhuravlev
 */
public final class JpsRemoteRepositoriesModelSerializerExtension extends JpsModelSerializerExtension{
  private static final JpsRemoteRepositoriesConfigurationSerializer SERIALIZER_IMPL = new JpsRemoteRepositoriesConfigurationSerializer();

  @Override
  public @NotNull List<? extends JpsProjectExtensionSerializer> getProjectExtensionSerializers() {
    return Collections.singletonList(SERIALIZER_IMPL);
  }
}
