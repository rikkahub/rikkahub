package me.rerere.document.pdf

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 标准安全处理器（Standard security handler，R2-R6）。
 * 只支持空用户密码或给定密码；仅设置了权限限制（owner 密码）的文件可直接打开。
 */
internal class PdfSecurity(encrypt: PdfDict, fileId: ByteArray, password: String) {
    private enum class Method { NONE, RC4, AES128, AES256 }

    private val revision = encrypt.num("R") ?: 2
    private val version = encrypt.num("V") ?: 0
    private val keyLength: Int
    private val stringMethod: Method
    private val streamMethod: Method
    private val fileKey: ByteArray

    init {
        val filter = (encrypt["Filter"] as? PdfName)?.name
        if (filter != null && filter != "Standard") throw PdfException("Unsupported security handler: $filter")
        val o = (encrypt["O"] as? PdfString)?.bytes ?: throw PdfException("Encrypt dictionary missing /O")
        val u = (encrypt["U"] as? PdfString)?.bytes ?: throw PdfException("Encrypt dictionary missing /U")
        val p = encrypt.num("P") ?: 0
        val encryptMetadata = (encrypt["EncryptMetadata"] as? PdfBoolean)?.value ?: true

        if (version >= 4) {
            val cf = encrypt["CF"] as? PdfDict
            stringMethod = cryptMethod(cf, (encrypt["StrF"] as? PdfName)?.name)
            streamMethod = cryptMethod(cf, (encrypt["StmF"] as? PdfName)?.name)
        } else {
            stringMethod = Method.RC4
            streamMethod = Method.RC4
        }
        keyLength = when {
            revision >= 5 -> 32
            revision == 2 -> 5
            else -> ((encrypt.num("Length") ?: 40) / 8).coerceIn(5, 16)
        }

        val pwd = password.toByteArray(if (revision >= 5) Charsets.UTF_8 else Charsets.ISO_8859_1)
        fileKey = if (revision >= 5) {
            val oe = (encrypt["OE"] as? PdfString)?.bytes
            val ue = (encrypt["UE"] as? PdfString)?.bytes
            aes256Key(pwd, o, u, oe, ue) ?: throw PdfException("PDF is password protected")
        } else {
            rc4Key(pwd, o, u, p, fileId, encryptMetadata) ?: throw PdfException("PDF is password protected")
        }
    }

    private fun PdfDict.num(key: String): Int? = (this[key] as? PdfNumber)?.value?.toLong()?.toInt()

    private fun cryptMethod(cf: PdfDict?, name: String?): Method {
        if (name == null || name == "Identity") return Method.NONE
        val filter = cf?.get(name) as? PdfDict ?: return Method.RC4
        return when ((filter["CFM"] as? PdfName)?.name) {
            "AESV2" -> Method.AES128
            "AESV3" -> Method.AES256
            "None" -> Method.NONE
            else -> Method.RC4
        }
    }

    fun decryptString(data: ByteArray, num: Int, gen: Int): ByteArray = decrypt(data, num, gen, stringMethod)

    fun decryptStream(data: ByteArray, num: Int, gen: Int): ByteArray = decrypt(data, num, gen, streamMethod)

    private fun decrypt(data: ByteArray, num: Int, gen: Int, method: Method): ByteArray {
        if (method == Method.NONE || data.isEmpty()) return data
        return try {
            when (method) {
                Method.AES256 -> aesDecrypt(fileKey, data)
                Method.AES128 -> aesDecrypt(objectKey(num, gen, aes = true), data)
                else -> rc4(objectKey(num, gen, aes = false), data)
            }
        } catch (_: Exception) {
            data
        }
    }

    private fun objectKey(num: Int, gen: Int, aes: Boolean): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(fileKey)
        md5.update(byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), gen.toByte(), (gen shr 8).toByte()))
        if (aes) md5.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54)) // "sAlT"
        return md5.digest().copyOf(minOf(fileKey.size + 5, 16))
    }

    // Algorithm 2 及用户/所有者密码校验（R2-R4）
    private fun rc4Key(pwd: ByteArray, o: ByteArray, u: ByteArray, p: Int, id: ByteArray, encryptMetadata: Boolean): ByteArray? {
        computeRc4Key(pad(pwd), o, p, id, encryptMetadata).takeIf { checkUser(it, u, id) }?.let { return it }
        // 把密码当作 owner 密码：用它解出 user 密码
        val ownerKey = md5Rounds(pad(pwd), if (revision >= 3) 51 else 1).copyOf(keyLength)
        var userPwd = o.copyOf(32)
        if (revision == 2) {
            userPwd = rc4(ownerKey, userPwd)
        } else {
            for (i in 19 downTo 0) {
                userPwd = rc4(ByteArray(ownerKey.size) { (ownerKey[it].toInt() xor i).toByte() }, userPwd)
            }
        }
        return computeRc4Key(userPwd, o, p, id, encryptMetadata).takeIf { checkUser(it, u, id) }
    }

    private fun computeRc4Key(paddedPwd: ByteArray, o: ByteArray, p: Int, id: ByteArray, encryptMetadata: Boolean): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(paddedPwd)
        md5.update(o.copyOf(32))
        md5.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
        md5.update(id)
        if (revision >= 4 && !encryptMetadata) md5.update(byteArrayOf(-1, -1, -1, -1))
        var key = md5.digest().copyOf(keyLength)
        if (revision >= 3) {
            repeat(50) { key = MessageDigest.getInstance("MD5").digest(key).copyOf(keyLength) }
        }
        return key
    }

    private fun checkUser(key: ByteArray, u: ByteArray, id: ByteArray): Boolean {
        if (revision == 2) return rc4(key, PADDING).contentEquals(u.copyOf(32))
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(PADDING)
        md5.update(id)
        var hash = rc4(key, md5.digest())
        for (i in 1..19) {
            hash = rc4(ByteArray(key.size) { (key[it].toInt() xor i).toByte() }, hash)
        }
        return u.size >= 16 && hash.contentEquals(u.copyOf(16))
    }

    private fun md5Rounds(data: ByteArray, rounds: Int): ByteArray {
        var d = data
        repeat(rounds) { d = MessageDigest.getInstance("MD5").digest(d) }
        return d
    }

    // R5/R6（AES-256）
    private fun aes256Key(pwd: ByteArray, o: ByteArray, u: ByteArray, oe: ByteArray?, ue: ByteArray?): ByteArray? {
        val password = pwd.copyOf(minOf(pwd.size, 127))
        if (o.size < 48 || u.size < 48) return null
        val u48 = u.copyOf(48)
        // owner 密码
        if (oe != null && hash(password, o.copyOfRange(32, 40), u48).contentEquals(o.copyOf(32))) {
            return aesNoPadding(hash(password, o.copyOfRange(40, 48), u48), oe.copyOf(32))
        }
        // user 密码
        if (ue != null && hash(password, u.copyOfRange(32, 40), ByteArray(0)).contentEquals(u.copyOf(32))) {
            return aesNoPadding(hash(password, u.copyOfRange(40, 48), ByteArray(0)), ue.copyOf(32))
        }
        return null
    }

    // Algorithm 2.A / 2.B
    private fun hash(password: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray {
        var k = MessageDigest.getInstance("SHA-256").digest(password + salt + udata)
        if (revision < 6) return k
        var round = 0
        while (true) {
            val block = password + k + udata
            val k1 = ByteArray(block.size * 64)
            for (i in 0 until 64) System.arraycopy(block, 0, k1, i * block.size, block.size)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
            val e = cipher.doFinal(k1)
            var sum = 0
            for (i in 0 until 16) sum += e[i].toInt() and 0xFF
            k = MessageDigest.getInstance(
                when (sum % 3) {
                    0 -> "SHA-256"
                    1 -> "SHA-384"
                    else -> "SHA-512"
                }
            ).digest(e)
            round++
            if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
        }
        return k.copyOf(32)
    }

    private fun aesNoPadding(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(data)
    }

    private fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 32 || data.size % 16 != 0) {
            // 只有 IV 或长度不对齐，按无填充解密可解的部分
            if (data.size <= 16) return ByteArray(0)
            val len = (data.size - 16) / 16 * 16
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data.copyOf(16)))
            return cipher.doFinal(data, 16, len)
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data.copyOf(16)))
        val plain = cipher.doFinal(data, 16, data.size - 16)
        // 手动去掉 PKCS#5 填充，填充损坏时保留原文
        val pad = plain.last().toInt() and 0xFF
        return if (pad in 1..16 && pad <= plain.size) plain.copyOf(plain.size - pad) else plain
    }

    companion object {
        private val PADDING = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(),
            0xFA.toByte(), 0x01, 0x08, 0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(), 0x2F,
            0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A
        )

        private fun pad(pwd: ByteArray): ByteArray {
            val out = ByteArray(32)
            val n = minOf(32, pwd.size)
            System.arraycopy(pwd, 0, out, 0, n)
            System.arraycopy(PADDING, 0, out, n, 32 - n)
            return out
        }

        // RC4 在部分 Android 版本的 Cipher 中不可用，手写实现
        fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            val out = ByteArray(data.size)
            var i = 0
            j = 0
            for (k in data.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
                out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
            return out
        }
    }
}
