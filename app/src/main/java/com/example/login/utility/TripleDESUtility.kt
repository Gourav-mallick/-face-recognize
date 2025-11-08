package com.example.login.utility

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class TripleDESUtility {


    private val privateKey = "digzaDigit"
    private val cipherDetails = "DESede/ECB/PKCS7Padding"
    private val algoDetails = "DESede"


    @Throws(Exception::class)
    fun getEncryptedStr(message: String): String {

        val md = MessageDigest.getInstance("MD5")
        val digestOfPassword = md.digest(privateKey.toByteArray(Charsets.UTF_8))

        val keyBytes = Arrays.copyOf(digestOfPassword, 24)
        for (j in 0 until 8) {
            keyBytes[j + 16] = keyBytes[j]
        }

        val key: SecretKey = SecretKeySpec(keyBytes, algoDetails)
        val cipher = Cipher.getInstance(cipherDetails)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val plainTextBytes = message.toByteArray(Charsets.UTF_8)
        val cipherText = cipher.doFinal(plainTextBytes)

        return Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    @Throws(Exception::class)
    fun getDecryptedStr(input: String): String {
        val message = Base64.decode(input, Base64.NO_WRAP)

        val md = MessageDigest.getInstance("MD5")
        val digestOfPassword = md.digest(privateKey.toByteArray(Charsets.UTF_8))

        val keyBytes = Arrays.copyOf(digestOfPassword, 24)
        Log.i("Decryption", "Before decryptedStr: ${keyBytes.size}")

        for (j in 0 until 8) {
            keyBytes[j + 16] = keyBytes[j]
        }

        Log.i("Decryption", "After decryptedStr: ${keyBytes.size}")

        val key: SecretKey = SecretKeySpec(keyBytes, algoDetails)
        Log.i("Decryption", "Secret privateKey: $key")

        val decipher = Cipher.getInstance(cipherDetails)
        decipher.init(Cipher.DECRYPT_MODE, key)

        val plainText = decipher.doFinal(message)

        return String(plainText, Charsets.UTF_8)
    }
}