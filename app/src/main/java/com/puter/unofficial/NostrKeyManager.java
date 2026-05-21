package com.puter.unofficial;

import android.util.Log;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.Security;

/**
 * Cryptographic Utility for Nostr Identity.
 * Responsible for generating Secp256k1 keypairs compatible with the 
 * decentralized Nostr protocol (BIP-340 Schnorr signatures).
 * Ensures Y-coordinate parity check to produce valid BIP-340 keys.
 */
public class NostrKeyManager {

    private static final String TAG = "Puter_KeyManager";

    static {
        // Register BouncyCastle as a security provider for Elliptic Curve operations
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Generates a new 32-byte private key and its corresponding 32-byte 
     * x-only public key.
     * 
     * @return String array: [0] = Private Key (Hex), [1] = Public Key (Hex)
     */
    public static String[] generateKeyPair() {
        try {
            // 1. Setup the Secp256k1 Curve parameters
            X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
            BigInteger n = params.getN();

            // 2. Generate a cryptographically secure 32-byte random number (The Private Key)
            SecureRandom secureRandom = new SecureRandom();
            BigInteger privateKeyInt;
            do {
                privateKeyInt = new BigInteger(256, secureRandom);
            } while (privateKeyInt.compareTo(n) >= 0 || privateKeyInt.equals(BigInteger.ZERO));

            // 3. Derive the Public Key Point (P = k * G)
            ECPoint publicKeyPoint = params.getG().multiply(privateKeyInt).normalize();

            // 4. BIP-340 / Nostr Requirement
            // Check if the Y-coordinate is ODD. If it is, we must negate the private key.
            // This ensures we always use the 'Even' coordinate version of the identity.
            if (publicKeyPoint.getAffineYCoord().toBigInteger().testBit(0)) {
                privateKeyInt = n.subtract(privateKeyInt);
                // Recalculate point (optional for X, but ensures point consistency)
                publicKeyPoint = params.getG().multiply(privateKeyInt).normalize();
            }

            // 5. Extract the X-coordinate (Nostr uses 32-byte x-only public keys)
            byte[] publicKeyBytes = publicKeyPoint.getAffineXCoord().getEncoded();
            if (publicKeyBytes.length > 32) {
                byte[] tmp = new byte[32];
                System.arraycopy(publicKeyBytes, publicKeyBytes.length - 32, tmp, 0, 32);
                publicKeyBytes = tmp;
            }

            // 6. Ensure raw 32-byte private key array (Handling sign-byte 0x00)
            byte[] rawPrivKey = privateKeyInt.toByteArray();
            if (rawPrivKey.length == 33 && rawPrivKey[0] == 0) {
                byte[] cleanPrivKey = new byte[32];
                System.arraycopy(rawPrivKey, 1, cleanPrivKey, 0, 32);
                rawPrivKey = cleanPrivKey;
            } else if (rawPrivKey.length < 32) {
                byte[] paddedPrivKey = new byte[32];
                System.arraycopy(rawPrivKey, 0, paddedPrivKey, 32 - rawPrivKey.length, rawPrivKey.length);
                rawPrivKey = paddedPrivKey;
            }

            // 7. Convert keys to Hexadecimal strings
            String privateKeyHex = bytesToHex(rawPrivKey);
            String publicKeyHex = bytesToHex(publicKeyBytes);

            // 8. STRICT NORMALIZATION: Ensure strings are exactly 64 characters
            privateKeyHex = normalizeHex(privateKeyHex, 64);
            publicKeyHex = normalizeHex(publicKeyHex, 64);

            Log.i(TAG, "Identity Generation Success. PubKey: " + publicKeyHex);
            return new String[]{privateKeyHex, publicKeyHex};

        } catch (Exception e) {
            Log.e(TAG, "Cryptographic failure during key generation: " + e.getMessage());
            throw new RuntimeException("Identity Generation Failed: " + e.getMessage(), e);
        }
    }

    /**
     * Helper to normalize hexadecimal strings to exact length, padding with zeroes if required.
     */
    private static String normalizeHex(String hex, int length) {
        if (hex == null) return "";
        if (hex.length() > length) {
            return hex.substring(hex.length() - length);
        } else if (hex.length() < length) {
            StringBuilder sb = new StringBuilder();
            while (sb.length() + hex.length() < length) {
                sb.append("0");
            }
            sb.append(hex);
            return sb.toString();
        }
        return hex;
    }

    /**
     * Converts a hexadecimal string back to a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        if (hex.length() % 2 != 0) hex = "0" + hex;
        
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Converts a byte array to a hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}