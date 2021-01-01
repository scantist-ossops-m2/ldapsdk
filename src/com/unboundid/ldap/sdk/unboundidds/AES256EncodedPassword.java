/*
 * Copyright 2020-2021 Ping Identity Corporation
 * All Rights Reserved.
 */
/*
 * Copyright 2020-2021 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright (C) 2020-2021 Ping Identity Corporation
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPLv2 only)
 * or the terms of the GNU Lesser General Public License (LGPLv2.1 only)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 */
package com.unboundid.ldap.sdk.unboundidds;



import java.io.Serializable;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

import com.unboundid.util.Base64;
import com.unboundid.util.ByteStringBuffer;
import com.unboundid.util.Debug;
import com.unboundid.util.NotMutable;
import com.unboundid.util.NotNull;
import com.unboundid.util.StaticUtils;
import com.unboundid.util.ThreadSafety;
import com.unboundid.util.ThreadSafetyLevel;
import com.unboundid.util.Validator;

import static com.unboundid.ldap.sdk.unboundidds.UnboundIDDSMessages.*;



/**
 * This class provides a mechanism that can be used to encrypt and decrypt
 * passwords using the same mechanism that the Ping Identity Directory Server
 * uses for the AES256 password storage scheme (for clients that know the
 * passphrase used to generate the encryption key).
 * <BR>
 * <BLOCKQUOTE>
 *   <B>NOTE:</B>  This class, and other classes within the
 *   {@code com.unboundid.ldap.sdk.unboundidds} package structure, are only
 *   supported for use against Ping Identity, UnboundID, and
 *   Nokia/Alcatel-Lucent 8661 server products.  These classes provide support
 *   for proprietary functionality or for external specifications that are not
 *   considered stable or mature enough to be guaranteed to work in an
 *   interoperable way with other types of LDAP servers.
 * </BLOCKQUOTE>
 * <BR>
 * Note that this class requires strong encryption support in the underlying
 * JVM.  For Java 7 JVMs, and for Java 8 JVMs prior to update 161, this requires
 * installing unlimited strength jurisdiction policy files in the JVM.  For Java
 * 8 JVMs starting with update 161, and for all later Java versions, strong
 * encryption should be available by default.
 * <BR><BR>
 * The raw representation for encoded passwords is constructed as follows:
 * <OL>
 *   <LI>
 *     A single byte that combines the encoding version and the padding length.
 *     The least significant four bits represent a two's complement integer that
 *     indicate the number of zero bytes that will be appended to the provided
 *     password to make it a multiple of sixteen bytes.  The most significant
 *     four bits represent the encoding version.  At present, we only support a
 *     single encoding version in which all of those bits must be set to zero.
 *     With this encoding version, the following properties will be used:
 *     <UL>
 *       <LI>Cipher Transformation:  AES/GCM/NoPadding</LI>
 *       <LI>Key Factory Algorithm:  PBKDF2WithHmacSHA512</LI>
 *       <LI>Key Factory Iteration Count:  32,768</LI>
 *       <LI>Key Factory Salt Length:  128 bits (16 bytes)</LI>
 *       <LI>Key Factory Generated Key length:  256 bits (32 bytes)</LI>
 *       <LI>Initialization Vector Length:  128 bits (16 bytes)</LI>
 *       <LI>GCM Tag Length:  128 bits</LI>
 *       <LI>Padding Modulus:  16</LI>
 *     </UL>
 *   </LI>
 *   <LI>
 *     Sixteen bytes of random data generated by a secure random number
 *     generator.  This represents the salt provided to the key factory for the
 *     purpose of generating the secret key.
 *   </LI>
 *   <LI>
 *     Sixteen bytes of random data generated by a secure random number
 *     generator.  This represents the initialization vector that will be used
 *     to randomize the cipher output.
 *   </LI>
 *   <LI>
 *     One byte that represents a two's complement integer that indicates the
 *     number of bytes in the ID of the encryption settings definition whose
 *     passphrase is used to generate the encryption key.  The value must be
 *     less than or equal to 255.  For current versions of the Ping Identity
 *     Directory Server, it will typically be 32 bytes.
 *   </LI>
 *   <LI>
 *     The bytes that comprise the raw ID of the encryption settings definition
 *     whose passphrase will be used to generate the encryption key.
 *   </LI>
 *   <LI>
 *     The bytes that comprise the encrypted password using the above settings.
 *   </LI>
 * </OL>
 * <BR>
 * The string representation of the encoded password is generated by appending
 * the base64-encoded representation of the raw encoded bytes to the prefix
 * "{AES256}".
 * <BR><BR>
 * When encrypting a password using this algorithm, the first step is to
 * generate the encryption key.  This is done using a key factory, which
 * combines a passphrase (obtained from an encryption settings definition), an
 * iteration count, and a salt.
 * <BR><BR>
 * The second step is to apply any necessary padding to the password.  Because
 * AES used in Galois Counter mode (GCM) behaves as a stream cipher, the size of
 * the encrypted data can be used to determine the size of the plaintext that
 * was encrypted.  This is undesirable when encrypting passwords because it can
 * let an attacker know how long the user's password is.  Padding the password
 * (by appending enough zero bytes to make its length a multiple of sixteen
 * bytes) makes it impossible for an attacker to determine the size of the
 * clear-text password.
 * <BR><BR>
 * The final step is to perform the encryption.  A cipher is created using the
 * generated secret key and initialization vector, and it is used to encrypt the
 * padded password.
 * <BR><BR>
 * Because this encoding uses reversible encryption rather than a one-way
 * algorithm, there are two possible ways to verify that a provided plain-text
 * password matches an encoded representation.  Both involve decoding the
 * encoded representation of the password to extract the padding length, salt,
 * initialization vector, and encryption settings definition ID.  Then, you can
 * either encrypt the provided plaintext password with the same settings to
 * verify that it yields the same encoded representation, or you can decrypt
 * the encoded password and remove any padding to verify that it yields the same
 * plaintext representation.
 */
@NotMutable()
@ThreadSafety(level=ThreadSafetyLevel.COMPLETELY_THREADSAFE)
public final class AES256EncodedPassword
       implements Serializable
{
  /**
   * The bitmask that will be used to indicate an encoding version of zero.
   * Only the most significant four bits of this byte will be used.  The least
   * significant four bits of the first byte will be used to indicate the number
   * of padding bytes that must be appended to the clear-text password to make
   * its length a multiple of sixteen bytes.
   */
  public static final byte ENCODING_VERSION_0_MASK = (byte) 0x00;



  /**
   * The integer value for encoding version 0.
   */
  public static final int ENCODING_VERSION_0 = 0;



  /**
   * The GCM tag length in bits to use with an encoding version of zero.
   */
  public static final int ENCODING_VERSION_0_GCM_TAG_LENGTH_BITS = 128;



  /**
   * The generated secret key length in bits to use with an encoding version of
   * zero.
   */
  public static final int ENCODING_VERSION_0_GENERATED_KEY_LENGTH_BITS = 256;



  /**
   * The size in bytes to use for the initialization vector with an encoding
   * version of zero.
   */
  public static final int ENCODING_VERSION_0_IV_LENGTH_BYTES = 16;



  /**
   * The key factory iteration count to use with an encoding version of zero.
   */
  public static final int ENCODING_VERSION_0_KEY_FACTORY_ITERATION_COUNT =
       32_768;



  /**
   * The size in bytes to use for the key factory salt with an encoding version
   * of zero.
   */
  public static final int ENCODING_VERSION_0_KEY_FACTORY_SALT_LENGTH_BYTES = 16;



  /**
   * The padding modulus to use with an encoding version of zero.
   */
  public static final int ENCODING_VERSION_0_PADDING_MODULUS = 16;



  /**
   * The name of the cipher algorithm that should be used with an encoding
   * version of zero.
   */
  @NotNull public static final String ENCODING_VERSION_0_CIPHER_ALGORITHM =
       "AES";



  /**
   * The name of the cipher transformation that should be used with an encoding
   * version of zero.
   */
  @NotNull public static final String ENCODING_VERSION_0_CIPHER_TRANSFORMATION =
       "AES/GCM/NoPadding";



  /**
   * The name of the key factory algorithm should be used with an encoding
   * version of zero.
   */
  @NotNull public static final String ENCODING_VERSION_0_KEY_FACTORY_ALGORITHM =
       "PBKDF2WithHmacSHA512";



  /**
   * The prefix that will appear at the beginning of the string representation
   * for an encoded password.
   */
  @NotNull public static final String PASSWORD_STORAGE_SCHEME_PREFIX =
       "{AES256}";



  /**
   * The serial version UID for this serializable class.
   */
  private static final long serialVersionUID = 8663129897722695672L;



  // The bytes that comprise the complete raw encoded representation of the
  // password.
  @NotNull private final byte[] encodedRepresentation;

  // The bytes that comprise the encrypted representation of the padded
  // password.
  @NotNull private final byte[] encryptedPaddedPassword;

  // The bytes that comprise the raw encryption settings definition ID whose
  // passphrase was used to generate the encoded password.
  @NotNull private final byte[] encryptionSettingsDefinitionID;

  // The initialization vector used to randomize the output of the encrypted
  // password.
  @NotNull private final byte[] initializationVector;

  // The salt used in the course of generating the encryption key from the
  // encryption settings definition passphrase.
  @NotNull private final byte[] keyFactorySalt;

  // The encoding version used for the password.
  private final int encodingVersion;

  // The number of zero bytes that were appended to the clear-text password
  // before it was encrypted.
  private final int paddingBytes;



  /**
   * Creates a new encoded password with the provided information.
   *
   * @param  encodedRepresentation
   *              The bytes that comprise the complete raw encoded
   *              representation of the password.
   * @param  encodingVersion
   *              The encoding version for this encoded password.
   * @param  paddingBytes
   *              The number of bytes of padding that need to be appended to the
   *              clear-text password to make its length a multiple of sixteen
   *              bytes.
   * @param  keyFactorySalt
   *              The salt used to generate the encryption key from the
   *              encryption settings definition passphrase.
   * @param  initializationVector
   *              The initialization vector used to randomize the cipher output.
   * @param  encryptionSettingsDefinitionID
   *              The bytes that comprise the raw encryption settings definition
   *              ID whose passphrase was used to generate the encoded password.
   * @param  encryptedPaddedPassword
   *              The bytes that comprise the encrypted representation of the
   *              padded password.
   */
  private AES256EncodedPassword(
               @NotNull final byte[] encodedRepresentation,
               final int encodingVersion,
               final int paddingBytes,
               @NotNull final byte[] keyFactorySalt,
               @NotNull final byte[] initializationVector,
               @NotNull final byte[] encryptionSettingsDefinitionID,
               @NotNull final byte[] encryptedPaddedPassword)
  {
    this.encodedRepresentation = encodedRepresentation;
    this.encodingVersion = encodingVersion;
    this.paddingBytes = paddingBytes;
    this.keyFactorySalt = keyFactorySalt;
    this.initializationVector = initializationVector;
    this.encryptionSettingsDefinitionID = encryptionSettingsDefinitionID;
    this.encryptedPaddedPassword = encryptedPaddedPassword;
  }



  /**
   * Retrieves the encoding version for this encoded password.
   *
   * @return  The encoding version for this encoded password.
   */
  public int getEncodingVersion()
  {
    return encodingVersion;
  }



  /**
   * Retrieves the number of bytes of padding that need to be appended to the
   * clear-text password to make its length a multiple of sixteen bytes.
   *
   * @return  The number of bytes of padding that need to be appended to the
   *          clear-text password to make its length a multiple of sixteen
   *          bytes.
   */
  public int getPaddingBytes()
  {
    return paddingBytes;
  }



  /**
   * Retrieves the salt used to generate the encryption key from the encryption
   * settings definition passphrase.
   *
   * @return  The salt used to generate the encryption key from the encryption
   *          settings definition passphrase.
   */
  @NotNull()
  public byte[] getKeyFactorySalt()
  {
    return keyFactorySalt;
  }



  /**
   * Retrieves the initialization vector used to randomize the cipher output.
   *
   * @return  The initialization vector used to randomize the cipher output.
   */
  @NotNull()
  public byte[] getInitializationVector()
  {
    return initializationVector;
  }



  /**
   * Retrieves the bytes that comprise the raw ID of the encryption settings
   * definition whose passphrase is used to generate the encryption key.
   *
   * @return  A bytes that comprise the raw ID of the encryption settings
   *          definition whose passphrase is used to generate the encryption
   *          key.
   */
  @NotNull()
  public byte[] getEncryptionSettingsDefinitionIDBytes()
  {
    return encryptionSettingsDefinitionID;
  }



  /**
   * Retrieves a string representation of the ID of the encryption settings
   * definition whose passphrase is used to generate the encryption key.
   *
   * @return  A string representation of the ID of the encryption settings
   *          definition whose passphrase is used to generate the encryption
   *          key.
   */
  @NotNull()
  public String getEncryptionSettingsDefinitionIDString()
  {
    return StaticUtils.toUpperCase(
         StaticUtils.toHex(encryptionSettingsDefinitionID));
  }



  /**
   * Retrieves the bytes that comprise the complete raw encoded representation
   * of the password.
   *
   * @return  The bytes that comprise the complete raw encoded representation of
   *          the password.
   */
  @NotNull()
  public byte[] getEncodedRepresentation()
  {
    return encodedRepresentation;
  }



  /**
   * Retrieves the string representation of this AES256 password.
   *
   * @param  includeScheme  Indicates whether to include the "{AES256}" prefix
   *                        at the beginning of the string representation.
   *
   * @return  The string representation of this encoded password.
   */
  @NotNull()
  public String getStringRepresentation(final boolean includeScheme)
  {
    final String base64String = Base64.encode(encodedRepresentation);
    if (includeScheme)
    {
      return PASSWORD_STORAGE_SCHEME_PREFIX + base64String;
    }
    else
    {
      return base64String;
    }
  }



  /**
   * Encodes a password using the provided information.
   *
   * @param  encryptionSettingsDefinitionID
   *              A string with the hexadecimal representation of the
   *              encryption settings definition whose passphrase was used to
   *              generate the encoded password.  It must not be
   *              {@code null} or empty, and it must represent a valid
   *              hexadecimal string whose length is an even number less than
   *              or equal to 510 bytes.
   * @param  encryptionSettingsDefinitionPassphrase
   *              The passphrase associated with the specified encryption
   *              settings definition.  It must not be {@code null} or empty.
   * @param  clearTextPassword
   *              The clear-text password to encode.  It must not be
   *              {@code null} or empty.
   *
   * @return  An object with all of the encoded password details.
   *
   * @throws  GeneralSecurityException  If a problem occurs while attempting to
   *                                    perform any of the cryptographic
   *                                    processing.
   *
   * @throws  ParseException  If the provided encryption settings definition ID
   *                          cannot be parsed as a valid hexadecimal string.
   */
  @NotNull()
  public static AES256EncodedPassword encode(
              @NotNull final String encryptionSettingsDefinitionID,
              @NotNull final String encryptionSettingsDefinitionPassphrase,
              @NotNull final String clearTextPassword)
         throws GeneralSecurityException, ParseException
  {
    final byte[] encryptionSettingsDefinitionIDBytes =
         StaticUtils.fromHex(encryptionSettingsDefinitionID);

    final char[] encryptionSettingsDefinitionPassphraseChars =
         encryptionSettingsDefinitionPassphrase.toCharArray();
    final byte[] clearTextPasswordBytes =
         StaticUtils.getBytes(clearTextPassword);

    try
    {
      return encode(encryptionSettingsDefinitionIDBytes,
           encryptionSettingsDefinitionPassphraseChars, clearTextPasswordBytes);
    }
    finally
    {
      Arrays.fill(encryptionSettingsDefinitionPassphraseChars, '\u0000');
      Arrays.fill(clearTextPasswordBytes, (byte) 0x00);
    }
  }



  /**
   * Encodes a password using the provided information.
   *
   * @param  encryptionSettingsDefinitionID
   *              The bytes that comprise the raw encryption settings definition
   *              ID whose passphrase was used to generate the encoded password.
   *              It must not be {@code null} or empty, and its length must be
   *              less than or equal to 255 bytes.
   * @param  encryptionSettingsDefinitionPassphrase
   *              The passphrase associated with the specified encryption
   *              settings definition.  It must not be {@code null} or empty.
   * @param  clearTextPassword
   *              The bytes that comprise the clear-text password to encode.
   *              It must not be {@code null} or empty.
   *
   * @return  An object with all of the encoded password details.
   *
   * @throws  GeneralSecurityException  If a problem occurs while attempting to
   *                                    perform any of the cryptographic
   *                                    processing.
   */
  @NotNull()
  public static AES256EncodedPassword encode(
              @NotNull final byte[] encryptionSettingsDefinitionID,
              @NotNull final char[] encryptionSettingsDefinitionPassphrase,
              @NotNull final byte[] clearTextPassword)
         throws GeneralSecurityException
  {
    final SecureRandom random = new SecureRandom();

    final byte[] keyFactorySalt =
         new byte[ENCODING_VERSION_0_KEY_FACTORY_SALT_LENGTH_BYTES];
    random.nextBytes(keyFactorySalt);

    final byte[] initializationVector =
         new byte[ENCODING_VERSION_0_IV_LENGTH_BYTES];
    random.nextBytes(initializationVector);

    return encode(encryptionSettingsDefinitionID,
         encryptionSettingsDefinitionPassphrase, keyFactorySalt,
         initializationVector, clearTextPassword);
  }



  /**
   * Encodes a password using the provided information.
   *
   * @param  encryptionSettingsDefinitionID
   *              The bytes that comprise the raw encryption settings definition
   *              ID whose passphrase was used to generate the encoded password.
   *              It must not be {@code null} or empty, and its length must be
   *              less than or equal to 255 bytes.
   * @param  encryptionSettingsDefinitionPassphrase
   *              The passphrase associated with the specified encryption
   *              settings definition.  It must not be {@code null} or empty.
   * @param  keyFactorySalt
   *              The salt used to generate the encryption key from the
   *              encryption settings definition passphrase.  It must not be
   *              {@code null} and it must have a length of exactly 16 bytes.
   * @param  initializationVector
   *              The initialization vector used to randomize the cipher output.
   *              It must not be [@code null} and it must have a length of
   *              exactly 16 bytes.
   * @param  clearTextPassword
   *              The bytes that comprise the clear-text password to encode.
   *              It must not be {@code null} or empty.
   *
   * @return  An object with all of the encoded password details.
   *
   * @throws  GeneralSecurityException  If a problem occurs while attempting to
   *                                    perform any of the cryptographic
   *                                    processing.
   */
  @NotNull()
  public static AES256EncodedPassword encode(
              @NotNull final byte[] encryptionSettingsDefinitionID,
              @NotNull final char[] encryptionSettingsDefinitionPassphrase,
              @NotNull final byte[] keyFactorySalt,
              @NotNull final byte[] initializationVector,
              @NotNull final byte[] clearTextPassword)
         throws GeneralSecurityException
  {
    final AES256EncodedPasswordSecretKey secretKey =
         AES256EncodedPasswordSecretKey.generate(encryptionSettingsDefinitionID,
              encryptionSettingsDefinitionPassphrase, keyFactorySalt);
    try
    {
      return encode(secretKey, initializationVector, clearTextPassword);
    }
    finally
    {
      secretKey.destroy();
    }
  }



  /**
   * Encodes a password using the provided information.
   *
   * @param  secretKey
   *              The secret key that should be used to encrypt the password.
   *              It must not be {@code null}.  The secret key can be reused
   *              when
   * @param  initializationVector
   *              The initialization vector used to randomize the cipher output.
   *              It must not be [@code null} and it must have a length of
   *              exactly 16 bytes.
   * @param  clearTextPassword
   *              The bytes that comprise the clear-text password to encode.
   *              It must not be {@code null} or empty.
   *
   * @return  An object with all of the encoded password details.
   *
   * @throws  GeneralSecurityException  If a problem occurs while attempting to
   *                                    perform any of the cryptographic
   *                                    processing.
   */
  @NotNull()
  public static AES256EncodedPassword encode(
              @NotNull final AES256EncodedPasswordSecretKey secretKey,
              @NotNull final byte[] initializationVector,
              @NotNull final byte[] clearTextPassword)
         throws GeneralSecurityException
  {
    // Validate all of the provided parameters.
    Validator.ensureNotNull(secretKey,
         "AES256EncodedPassword.encode.secretKey must not be null.");

    Validator.ensureNotNull(initializationVector,
         "AES256EncodedPassword.encode.initializationVector must not be null.");
    if (initializationVector.length != ENCODING_VERSION_0_IV_LENGTH_BYTES)
    {
      Validator.violation("AES256EncodedPassword.encode.initializationVector " +
           "must have a length of exactly " +
           ENCODING_VERSION_0_IV_LENGTH_BYTES + " bytes.  The provided " +
           "initialization vector had a length of " +
           initializationVector.length + " bytes.");
    }

    Validator.ensureNotNullOrEmpty(clearTextPassword,
         "AES256EncodedPassword.encode.clearTextPassword must not be null or " +
              "empty.");


    // Generate a padded representation of the password.
    final byte[] paddedClearTextPassword;
    final int paddingBytesNeeded;
    final int clearTextPasswordLengthModulus =
         clearTextPassword.length % ENCODING_VERSION_0_PADDING_MODULUS;
    if (clearTextPasswordLengthModulus == 0)
    {
      paddedClearTextPassword = clearTextPassword;
      paddingBytesNeeded = 0;
    }
    else
    {
      paddingBytesNeeded =
           ENCODING_VERSION_0_PADDING_MODULUS - clearTextPasswordLengthModulus;
      paddedClearTextPassword =
           new byte[clearTextPassword.length + paddingBytesNeeded];
      Arrays.fill(paddedClearTextPassword, (byte) 0x00);
      System.arraycopy(clearTextPassword, 0, paddedClearTextPassword, 0,
           clearTextPassword.length);
    }


    // Create and initialize the cipher and use it to encrypt the padded
    // password.
    final Cipher cipher =
         Cipher.getInstance(ENCODING_VERSION_0_CIPHER_TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey.getSecretKey(),
         new GCMParameterSpec(ENCODING_VERSION_0_GCM_TAG_LENGTH_BITS,
              initializationVector));
    final byte[] encryptedPaddedPassword =
         cipher.doFinal(paddedClearTextPassword);


    // Generate the raw encoded representation of the password.
    final ByteStringBuffer buffer = new ByteStringBuffer();

    // The first byte will combine the encoding version (the upper four bits)
    // and the number of padding bytes (the lower four bits).
    buffer.append((byte)
         ((ENCODING_VERSION_0_MASK & 0xF0) | (paddingBytesNeeded & 0x0F)));

    // The next 16 bytes will be the salt.
    final byte[] keyFactorySalt = secretKey.getKeyFactorySalt();
    buffer.append(keyFactorySalt);

    // The next 16 bytes will be the initialization vector.
    buffer.append(initializationVector);

    // The next byte will be the number of bytes in the raw encryption settings
    // definition ID, followed by the encoded ID.
    final byte[] encryptionSettingsDefinitionID =
         secretKey.getEncryptionSettingsDefinitionID();
    buffer.append((byte) (encryptionSettingsDefinitionID.length & 0xFF));
    buffer.append(encryptionSettingsDefinitionID);

    // The remainder of the encoded representation will be the encrypted
    // padded password.
    buffer.append(encryptedPaddedPassword);


    // Create and return an object with all of the encoded password details.
    return new AES256EncodedPassword(buffer.toByteArray(), ENCODING_VERSION_0,
         paddingBytesNeeded, keyFactorySalt, initializationVector,
         encryptionSettingsDefinitionID, encryptedPaddedPassword);
  }



  /**
   * Decodes the provided password into its component parts.
   *
   * @param  encodedPassword
   *              The string representation of the encoded password to be
   *              decoded.  It must not be {@code null} or empty, and it must
   *              contain the base64-encoded representation of the raw encoded
   *              password, optionally preceded by the "{AES256}" prefix.
   *
   * @return  The decoded representation of the provided password.
   *
   * @throws  ParseException  If the provided string does not represent a valid
   *                          encoded password.
   */
  @NotNull()
  public static AES256EncodedPassword decode(
              @NotNull final String encodedPassword)
         throws ParseException
  {
    Validator.ensureNotNullOrEmpty(encodedPassword,
         "AES256EncodedPassword.decode.encodedPassword must not be null or " +
              "empty.");


    // If the provided string starts with a prefix, then strip it off.
    final int base64StartPos;
    final String base64EncodedString;
    if (encodedPassword.startsWith(PASSWORD_STORAGE_SCHEME_PREFIX))
    {
      base64StartPos = PASSWORD_STORAGE_SCHEME_PREFIX.length();
      base64EncodedString = encodedPassword.substring(base64StartPos);
    }
    else
    {
      base64StartPos = 0;
      base64EncodedString = encodedPassword;
    }


    // Base64-decode the data.
    final byte[] encodedPasswordBytes;
    try
    {
      encodedPasswordBytes = Base64.decode(base64EncodedString);
    }
    catch (final ParseException e)
    {
      Debug.debugException(e);
      throw new ParseException(
           ERR_AES256_ENC_PW_DECODE_NOT_BASE64.get(
                StaticUtils.getExceptionMessage(e)),
           base64StartPos);
    }


    return decode(encodedPasswordBytes);
  }



  /**
   * Decodes the provided password into its component parts.
   *
   * @param  encodedPassword
   *              The bytes that comprise the complete raw encoded
   *              representation of the password.  It must not be {@code null}
   *              or empty.
   *
   * @return  The decoded representation of the provided password.
   *
   * @throws  ParseException  If the provided string does not represent a valid
   *                          encoded password.
   */
  @NotNull()
  public static AES256EncodedPassword decode(
              @NotNull final byte[] encodedPassword)
         throws ParseException
  {
    Validator.ensureNotNullOrEmpty(encodedPassword,
         "AES256EncodedPassword.decode.encodedPassword must not be null or " +
              "empty.");


    // Make sure that the length is at least 36 bytes long.  This isn't long
    // enough for a valid encoded password, but it's long enough to let us get a
    // good starting point.
    if (encodedPassword.length < 36)
    {
      throw new ParseException(
           ERR_AES256_ENC_PW_DECODE_TOO_SHORT_INITIAL.get(
                encodedPassword.length),
           0);
    }


    // The first byte must contain the encoding version and the number of bytes
    // of padding.
    final byte encodingVersionAndPaddingByte = encodedPassword[0];
    final int encodingVersion = (encodingVersionAndPaddingByte >> 4) & 0x0F;
    if (encodingVersion != ENCODING_VERSION_0)
    {
      throw new ParseException(
           ERR_AES256_ENC_PW_DECODE_UNSUPPORTED_ENCODING_VERSION.get(
                encodingVersion, ENCODING_VERSION_0),
           0);
    }

    final int paddingBytes = (encodingVersionAndPaddingByte & 0x0F);


    // The next 16 bytes must contain the salt.
    final byte[] keyFactorySalt =
         new byte[ENCODING_VERSION_0_KEY_FACTORY_SALT_LENGTH_BYTES];
    System.arraycopy(encodedPassword, 1, keyFactorySalt, 0,
         keyFactorySalt.length);


    // The next 16 bytes must contain the initialization vector.
    final byte[] initializationVector =
         new byte[ENCODING_VERSION_0_IV_LENGTH_BYTES];
    System.arraycopy(encodedPassword, 1 + keyFactorySalt.length,
         initializationVector, 0, initializationVector.length);


    // The next byte must indicate how many bytes are in the encryption settings
    // definition ID.  That should then be followed by the specified number of
    // bytes of the encryption settings definition ID.
    final int esdIDLengthPos =
         1 + keyFactorySalt.length + initializationVector.length;
    final int esdIDLength = encodedPassword[esdIDLengthPos] & 0xFF;
    if (encodedPassword.length < (esdIDLengthPos + 2 + esdIDLength))
    {
      throw new ParseException(
           ERR_AES256_ENC_PW_DECODE_TOO_SHORT_FOR_ESD_ID.get(
                encodedPassword.length, esdIDLength),
           esdIDLengthPos);
    }

    final byte[] encryptionSettingsDefinitionID = new byte[esdIDLength];
    System.arraycopy(encodedPassword, esdIDLengthPos + 1,
         encryptionSettingsDefinitionID, 0, esdIDLength);


    // The remainder of the encoded password must be the encrypted padded
    // password.
    final int encryptedPaddedPasswordPos =
         esdIDLengthPos + 1 + esdIDLength;
    final int encryptedPaddedPasswordLength = encodedPassword.length -
         encryptedPaddedPasswordPos;
    final byte[] encryptedPaddedPassword =
         new byte[encryptedPaddedPasswordLength];
    System.arraycopy(encodedPassword, encryptedPaddedPasswordPos,
         encryptedPaddedPassword, 0, encryptedPaddedPasswordLength);

    return new AES256EncodedPassword(encodedPassword, encodingVersion,
         paddingBytes, keyFactorySalt, initializationVector,
         encryptionSettingsDefinitionID, encryptedPaddedPassword);
  }



  /**
   * Decrypts this encoded password to obtain the original clear-text password
   * used to generate it.
   *
   * @param  encryptionSettingsDefinitionPassphrase
   *              The passphrase associated with the encryption settings
   *              definition used to encrypt the password.  It must not be
   *              {@code null} or empty.
   *
   * @return  The original clear-txt password used to generate this encoded
   *          representation.
   *
   * @throws  GeneralSecurityException  If an error occurs while attempting to
   *                                    decrypt the password using the
   *                                    provided encryption settings ID
   *                                    passphrase.
   */
  @NotNull()
  public byte[] decrypt(
              @NotNull final String encryptionSettingsDefinitionPassphrase)
         throws GeneralSecurityException
  {
    final char[] passphraseChars =
         encryptionSettingsDefinitionPassphrase.toCharArray();

    try
    {
      return decrypt(passphraseChars);
    }
    finally
    {
      Arrays.fill(passphraseChars, '\u0000');
    }
  }



  /**
   * Decrypts this encoded password to obtain the original clear-text password
   * used to generate it.
   *
   * @param  encryptionSettingsDefinitionPassphrase
   *              The passphrase associated with the encryption settings
   *              definition used to encrypt the password.  It must not be
   *              {@code null} or empty.
   *
   * @return  The original clear-txt password used to generate this encoded
   *          representation.
   *
   * @throws  GeneralSecurityException  If an error occurs while attempting to
   *                                    decrypt the password using the
   *                                    provided encryption settings ID
   *                                    passphrase.
   */
  @NotNull()
  public byte[] decrypt(
              @NotNull final char[] encryptionSettingsDefinitionPassphrase)
         throws GeneralSecurityException
  {
    final AES256EncodedPasswordSecretKey secretKey =
         AES256EncodedPasswordSecretKey.generate(encryptionSettingsDefinitionID,
              encryptionSettingsDefinitionPassphrase, keyFactorySalt);

    try
    {
      return decrypt(secretKey);
    }
    finally
    {
      secretKey.destroy();
    }
  }



  /**
   * Decrypts this encoded password to obtain the original clear-text password
   * used to generate it.
   *
   * @param  secretKey
   *              The that will be used to decrypt the password.  It must not
   *              be {@code null}.
   *
   * @return  The original clear-txt password used to generate this encoded
   *          representation.
   *
   * @throws  GeneralSecurityException  If an error occurs while attempting to
   *                                    decrypt the password using the
   *                                    provided encryption settings ID
   *                                    passphrase.
   */
  @NotNull()
  public byte[] decrypt(@NotNull final AES256EncodedPasswordSecretKey secretKey)
         throws GeneralSecurityException
  {
    Validator.ensureNotNull(secretKey,
         "AES256EncodedPassword.decrypt.secretKey must not be null.");


    // Create and initialize the cipher and use it to decrypt the padded
    // password.
    final Cipher cipher =
         Cipher.getInstance(ENCODING_VERSION_0_CIPHER_TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, secretKey.getSecretKey(),
         new GCMParameterSpec(ENCODING_VERSION_0_GCM_TAG_LENGTH_BITS,
              initializationVector));
    final byte[] decryptedPaddedPassword =
         cipher.doFinal(encryptedPaddedPassword);


    // Strip off any padding and return the resulting password.
    final byte[] decryptedPassword;
    if (paddingBytes > 0)
    {
      try
      {
        decryptedPassword =
             new byte[decryptedPaddedPassword.length - paddingBytes];
        for (int  i=0; i < decryptedPaddedPassword.length; i++)
        {
          if (i < decryptedPassword.length)
          {
            // This byte is not padding.
            decryptedPassword[i] = decryptedPaddedPassword[i];
          }
          else
          {
            // This byte is considered padding.  Make sure that it's 0x00.
            if (decryptedPaddedPassword[i] != 0x00)
            {
              throw new BadPaddingException(
                   ERR_AES256_ENC_PW_DECRYPT_NONZERO_PADDING.get(paddingBytes));
            }
          }
        }

        System.arraycopy(decryptedPaddedPassword, 0, decryptedPassword, 0,
             decryptedPassword.length);
      }
      finally
      {
        Arrays.fill(decryptedPaddedPassword, (byte) 0x00);
      }
    }
    else
    {
      decryptedPassword = decryptedPaddedPassword;
    }

    return decryptedPassword;
  }



  /**
   * Retrieves a string representation of this AES256 password.
   *
   * @return  A string representation of this encoded password.
   */
  @Override()
  @NotNull()
  public String toString()
  {
    final StringBuilder buffer = new StringBuilder();
    toString(buffer);
    return buffer.toString();
  }



  /**
   * Appends a string representation of this AES256 encoded password to the
   * provided buffer.
   *
   * @param  buffer  The buffer to which the information should be appended.
   */
  public void toString(@NotNull final StringBuilder buffer)
  {
    buffer.append("AES256EncodedPassword(stringRepresentation='");
    buffer.append(getStringRepresentation(true));
    buffer.append("', encodingVersion=");
    buffer.append(encodingVersion);
    buffer.append(", paddingBytes=");
    buffer.append(paddingBytes);
    buffer.append(", encryptionSettingsDefinitionIDHex='");
    StaticUtils.toHex(encryptionSettingsDefinitionID, buffer);
    buffer.append("', keyFactorySaltBytesHex='");
    StaticUtils.toHex(keyFactorySalt, buffer);
    buffer.append("', initializationVectorBytesHex='");
    StaticUtils.toHex(keyFactorySalt, buffer);
    buffer.append("')");
  }
}