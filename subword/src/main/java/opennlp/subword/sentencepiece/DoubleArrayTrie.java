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

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import opennlp.tools.util.InvalidFormatException;

/**
 * Read-only lookup over a serialized Darts-clone double-array trie, the dictionary format
 * embedded in a SentencePiece model's precompiled character map.
 *
 * <p>Each unit is one little-endian 32-bit word encoding a label, an offset to the unit's
 * children, and a leaf flag; traversal XORs the offset with the next key byte. Only the longest
 * prefix match is needed here, so this walks the byte key once and remembers the last accepting
 * state. The constructor checks that every unit a walk can reach lies inside the trie, so a
 * lookup never leaves it.</p>
 *
 * @see <a href="https://github.com/s-yata/darts-clone">Darts-clone</a>
 */
final class DoubleArrayTrie implements Serializable {

  private static final long serialVersionUID = -1572336116472261588L;

  // A non-leaf unit stores its transition label in the low 8 bits and the leaf flag in the sign
  // bit. Key bytes are in [0, 255] with the sign bit clear, so comparing (unit & this mask)
  // against a key byte both matches the label and rejects leaf units in one test.
  private static final int LEAF_FLAG_AND_LABEL_MASK = 0x800000FF;

  // A leaf unit stores the key's value in its low 31 bits; the sign bit is the leaf flag.
  private static final int LEAF_VALUE_MASK = 0x7FFFFFFF;

  // Bit 8 of a non-leaf unit marks that one of its children is a leaf holding this key's value.
  private static final int HAS_LEAF_BIT = 8;

  // Units are allocated in blocks of 256, one unit per value of a key byte.
  private static final int BLOCK_UNITS = 256;

  private final int[] units;

  /**
   * Wraps serialized trie units and checks that every reachable unit lies inside the trie.
   *
   * @param data   The bytes holding the units; must not be {@code null}.
   * @param offset The offset of the first unit byte.
   * @param length The number of bytes; the caller checks that it is a positive multiple of 1024,
   *               so that the units form whole blocks of {@value #BLOCK_UNITS}.
   * @throws InvalidFormatException Thrown if a unit references children outside the trie.
   */
  DoubleArrayTrie(byte[] data, int offset, int length) throws InvalidFormatException {
    units = new int[length / Integer.BYTES];
    for (int i = 0; i < units.length; i++) {
      final int base = offset + i * Integer.BYTES;
      units[i] = (data[base] & 0xFF) | (data[base + 1] & 0xFF) << 8
          | (data[base + 2] & 0xFF) << 16 | (data[base + 3] & 0xFF) << 24;
    }
    checkReachableUnits();
  }

  /**
   * Restores the units of a serialized trie and applies the check of the constructor to them.
   *
   * @param in The stream to read from.
   * @throws IOException Thrown if the stream cannot be read, or, as an
   *     {@link InvalidObjectException}, if a unit references children outside the trie.
   * @throws ClassNotFoundException Thrown if a class of the stream is not found.
   */
  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    if (units == null || units.length == 0 || units.length % BLOCK_UNITS != 0) {
      throw new InvalidObjectException("The trie units do not form whole blocks of "
          + BLOCK_UNITS + ".");
    }
    try {
      checkReachableUnits();
    } catch (InvalidFormatException e) {
      final InvalidObjectException invalid = new InvalidObjectException(e.getMessage());
      invalid.initCause(e);
      throw invalid;
    }
  }

  /**
   * Walks every unit reachable from the root and checks that its children lie inside the trie.
   * The units form whole blocks of {@value #BLOCK_UNITS}, and a key byte changes only the low
   * eight bits of a child position, so a children position inside the trie keeps every child
   * and leaf read of {@link #longestPrefixMatch} inside it.
   *
   * @throws InvalidFormatException Thrown if a reachable unit references children outside the
   *     trie.
   */
  private void checkReachableUnits() throws InvalidFormatException {
    final boolean[] visited = new boolean[units.length];
    // Every unit is pushed at most once, so the stack never holds more than all of them.
    final int[] pending = new int[units.length];
    int top = 0;
    pending[top++] = 0;
    visited[0] = true;
    while (top > 0) {
      final int node = pending[--top];
      final int children = node ^ offset(units[node]);
      if (children >= units.length) {
        throw new InvalidFormatException("The precompiled character map trie references a unit"
            + " outside its " + units.length + " units.");
      }
      for (int b = 0; b < BLOCK_UNITS; b++) {
        final int child = children ^ b;
        if (!visited[child] && (units[child] & LEAF_FLAG_AND_LABEL_MASK) == b) {
          visited[child] = true;
          pending[top++] = child;
        }
      }
    }
  }

  /**
   * Finds the longest key that is a prefix of {@code key[from, to)}.
   *
   * @param key  The byte key to match against; must not be {@code null}.
   * @param from The inclusive start of the query window.
   * @param to   The exclusive end of the query window.
   * @return {@code (value << 32) | matchedLength} for the longest match, or {@code -1} when no
   *     key matches. Values are non-negative, so the result is negative only on no-match.
   */
  long longestPrefixMatch(byte[] key, int from, int to) {
    long result = -1;
    int nodePos = offset(units[0]);
    for (int i = from; i < to; i++) {
      final int b = key[i] & 0xFF;
      nodePos ^= b;
      final int unit = units[nodePos];
      if ((unit & LEAF_FLAG_AND_LABEL_MASK) != b) {
        return result;
      }
      nodePos ^= offset(unit);
      if (((unit >>> HAS_LEAF_BIT) & 1) == 1) {
        final int value = units[nodePos] & LEAF_VALUE_MASK;
        result = ((long) value << 32) | (i - from + 1);
      }
    }
    return result;
  }

  /**
   * Tests whether any key starts with the given byte, which is exactly whether the root has a
   * transition on it; used to precompute the first-byte gate of the normalizer scan.
   *
   * @param b The first key byte as an unsigned value.
   * @return {@code true} if some key starts with {@code b}.
   */
  boolean hasTransitionFromRoot(int b) {
    return (units[offset(units[0]) ^ b] & LEAF_FLAG_AND_LABEL_MASK) == b;
  }

  /**
   * Returns the offset from a unit to its children, as encoded by Darts-clone: bits 10 to 30 hold
   * the raw offset, and bit 9 is an extension flag that scales it by 256 for far-away children.
   *
   * @param unit The unit word.
   * @return The child offset.
   */
  private int offset(int unit) {
    final int raw = unit >>> 10;
    return (unit & 1 << 9) == 0 ? raw : raw << 8;
  }
}
