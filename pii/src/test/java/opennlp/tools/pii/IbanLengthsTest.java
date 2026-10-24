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

package opennlp.tools.pii;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the packed IBAN registry for every country code, so that a typo in the table is
 * found without a bank-issued example for the country.
 */
class IbanLengthsTest {

  /** The shortest IBAN the registry assigns, Norway. */
  private static final int MIN_LENGTH = 15;

  /** The longest IBAN ISO 13616 allows. */
  private static final int MAX_LENGTH = 34;

  /** The number of entries the registry had when the table was generated. */
  private static final int MIN_REGISTERED = 80;

  /**
   * Provides every two-letter country code.
   *
   * @return The 676 codes.
   */
  private static Stream<String> countryCodes() {
    return IntStream.rangeClosed('A', 'Z').boxed().flatMap(first -> IntStream.rangeClosed('A', 'Z')
        .mapToObj(second -> "" + (char) first.intValue() + (char) second));
  }

  /**
   * Checks that a registered length lies within the ISO 13616 range and an unregistered
   * country has none.
   *
   * @param country The country code.
   */
  @ParameterizedTest
  @MethodSource("countryCodes")
  void testLengthIsWithinTheIsoRangeOrAbsent(String country) {
    final int length = IbanLengths.registeredLength(country.charAt(0), country.charAt(1));

    Assertions.assertTrue(length == 0 || (length >= MIN_LENGTH && length <= MAX_LENGTH),
        country + " has length " + length);
  }

  /** Checks that the table did not lose entries. */
  @Test
  void testRegistryIsSubstantial() {
    final long registered = countryCodes()
        .filter(country -> IbanLengths.registeredLength(country.charAt(0), country.charAt(1)) > 0)
        .count();

    Assertions.assertTrue(registered >= MIN_REGISTERED, "registered countries: " + registered);
  }

  /**
   * Checks published lengths at both ends of the range and for an unregistered code.
   *
   * @param country The country code.
   * @param length The registered length, or 0.
   */
  @ParameterizedTest
  @CsvSource({"NO,15", "BE,16", "DE,22", "FR,27", "MT,31", "LC,32", "RU,33", "US,0", "ZZ,0"})
  void testPublishedLengths(String country, int length) {
    Assertions.assertEquals(length, IbanLengths.registeredLength(country.charAt(0), country.charAt(1)));
  }
}
