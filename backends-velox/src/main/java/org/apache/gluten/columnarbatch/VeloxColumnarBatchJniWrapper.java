/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.columnarbatch;

import org.apache.gluten.runtime.Runtime;
import org.apache.gluten.runtime.RuntimeAware;

public class VeloxColumnarBatchJniWrapper implements RuntimeAware {
  private final Runtime runtime;

  private VeloxColumnarBatchJniWrapper(Runtime runtime) {
    this.runtime = runtime;
  }

  public static VeloxColumnarBatchJniWrapper create(Runtime runtime) {
    return new VeloxColumnarBatchJniWrapper(runtime);
  }

  public native long from(long batch);

  public native long compose(long[] batches);

  public native long slice(long veloxBatchHandle, int offset, int limit);

  @Override
  public long rtHandle() {
    return runtime.getHandle();
  }
}
