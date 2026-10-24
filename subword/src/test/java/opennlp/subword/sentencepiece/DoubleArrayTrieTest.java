/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package opennlp.subword.sentencepiece;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import opennlp.tools.util.InvalidFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that a {@link DoubleArrayTrie} rejects units outside the trie, both when it is built and
 * when it is deserialized.
 */
class DoubleArrayTrieTest {

  private static final int UNITS = 256;
  private static final String OUTSIDE =
      "The precompiled character map trie references a unit outside its 256 units.";
  // A root unit whose children start at unit 1000.
  private static final int OUTSIDE_ROOT = 1000 << 10;

  @Test
  void testEmptyTrieMatchesNothing() throws InvalidFormatException {
    final DoubleArrayTrie trie = new DoubleArrayTrie(units(0), 0, UNITS * Integer.BYTES);

    assertEquals(-1, trie.longestPrefixMatch(new byte[] {'a', 'b'}, 0, 2));
  }

  @Test
  void testRootOutsideTheTrieIsRejected() {
    final InvalidFormatException e = assertThrows(InvalidFormatException.class,
        () -> new DoubleArrayTrie(units(OUTSIDE_ROOT), 0, UNITS * Integer.BYTES));

    assertEquals(OUTSIDE, e.getMessage());
  }

  @Test
  void testDeserializedRootOutsideTheTrieIsRejected() throws IOException {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
      out.writeObject(new DoubleArrayTrie(units(0), 0, UNITS * Integer.BYTES));
    }
    final byte[] stream = buffer.toByteArray();
    // Java serialization writes the int[] as its length followed by big-endian values.
    final int root = indexOfUnits(stream) + Integer.BYTES;
    stream[root] = (byte) (OUTSIDE_ROOT >>> 24);
    stream[root + 1] = (byte) (OUTSIDE_ROOT >>> 16);
    stream[root + 2] = (byte) (OUTSIDE_ROOT >>> 8);
    stream[root + 3] = (byte) OUTSIDE_ROOT;

    final InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> {
      try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(stream))) {
        in.readObject();
      }
    });

    assertEquals(OUTSIDE, e.getMessage());
  }

  /**
   * Encodes 256 little-endian units, all zero except the root.
   *
   * @param root The root unit.
   * @return The unit bytes.
   */
  private byte[] units(int root) {
    final byte[] data = new byte[UNITS * Integer.BYTES];
    data[0] = (byte) root;
    data[1] = (byte) (root >>> 8);
    data[2] = (byte) (root >>> 16);
    data[3] = (byte) (root >>> 24);
    return data;
  }

  /**
   * Finds the length field of the serialized unit array: the int 256 followed by 256 zero units.
   *
   * @param stream The serialized trie.
   * @return The offset of the length field.
   */
  private int indexOfUnits(byte[] stream) {
    final byte[] pattern = new byte[Integer.BYTES * (UNITS + 1)];
    pattern[2] = 1;
    for (int i = 0; i + pattern.length <= stream.length; i++) {
      if (Arrays.equals(stream, i, i + pattern.length, pattern, 0, pattern.length)) {
        return i;
      }
    }
    throw new AssertionError("The serialized trie holds no unit array.");
  }
}
