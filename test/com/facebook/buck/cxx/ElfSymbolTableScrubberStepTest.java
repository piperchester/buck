/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cxx;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.elf.Elf;
import com.facebook.buck.cxx.elf.ElfSection;
import com.facebook.buck.cxx.elf.ElfSymbolTable;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

public class ElfSymbolTableScrubberStepTest {

  private static final String SECTION = ".dynsym";

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void test() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "elf_shared_lib", tmp);
    workspace.setUp();
    ElfSymbolTableScrubberStep step =
        ElfSymbolTableScrubberStep.of(
            new ProjectFilesystem(tmp.getRoot()),
            tmp.getRoot().getFileSystem().getPath("libfoo.so"),
            ".dynsym",
            /* allowMissing */ false);
    step.execute(TestExecutionContext.newInstance());

    // Verify that the symbol table values and sizes are zero.
    try (FileChannel channel =
         FileChannel.open(
             step.getFilesystem().resolve(step.getPath()),
             StandardOpenOption.READ)) {
      MappedByteBuffer buffer = channel.map(READ_ONLY, 0, channel.size());
      Elf elf = new Elf(buffer);
      ElfSection section =
          elf.getSectionByName(SECTION).orElseThrow(AssertionError::new).getSecond();
      ElfSymbolTable table = ElfSymbolTable.parse(elf.header.ei_class, section.body);
      Set<Long> addresses = new HashSet<>();
      table.entries.forEach(
          entry -> {
            // Addresses should either be 0, or a unique value.
            assertTrue(entry.st_value == 0 || addresses.add((entry.st_value)));
            assertThat(
                entry.st_shndx,
                Matchers.equalTo(
                    entry.st_shndx != 0 ?
                        ElfSymbolTableScrubberStep.STABLE_SECTION :
                        entry.st_shndx));
            assertThat(
                entry.st_size,
                Matchers.equalTo(
                    entry.st_info.st_type == ElfSymbolTable.Entry.Info.Type.STT_FUNC ?
                        0 :
                        entry.st_size));
          });
    }
  }

}
