/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package hat.backend;

import hat.ComputeContext;
import hat.KernelContext;
import hat.NDRange;
import hat.buffer.Buffer;
import hat.ifacemapper.BoundSchema;
import hat.ifacemapper.SegmentMapper;
import hat.callgraph.KernelEntrypoint;
import hat.callgraph.KernelCallGraph;
import java.lang.foreign.Arena;
import intel.code.spirv.LevelZero;

public class SpirvBackend extends JavaBackend {

    private final LevelZero levelZero;

    public SpirvBackend() {
        levelZero = LevelZero.create(arena);
    }

    public void info() {
        System.out.println("hat.backend.SpirvBackend v.0.1.0");
    }

    @Override
    public <T extends Buffer> T allocate(SegmentMapper<T> segmentMapper, BoundSchema<T> boundSchema){
        return segmentMapper.allocate(levelZero.arena(), boundSchema);
    }

    @Override
    public void computeContextHandoff(ComputeContext computeContext) {
    }

    @Override
    public void dispatchKernel(KernelCallGraph kernelCallGraph, NDRange ndRange, Object... args) {
        levelZero.dispatchKernel(kernelCallGraph, ndRange, args);
    }
}
