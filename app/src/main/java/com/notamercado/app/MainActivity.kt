package com.notamercado.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

object ImagemUtil {

    /** Carrega a foto já reduzida (lado maior <= maxLado) e na orientação correta. */
    fun reduzir(ctx: Context, uri: Uri, maxLado: Int = 2000): Bitmap {
        val cr = ctx.contentResolver
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val entrada = cr.openInputStream(uri) ?: throw IOException("Não foi possível abrir a imagem")
        entrada.use { BitmapFactory.decodeStream(it, null, limites) }
        if (limites.outWidth <= 0 || limites.outHeight <= 0) throw IOException("Formato de imagem não reconhecido")
        var amostra = 1
        while (max(limites.outWidth, limites.outHeight) / (amostra * 2) >= maxLado) amostra *= 2

        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = amostra })
        } ?: throw IOException("Imagem inválida")

        val maior = max(bmp.width, bmp.height)
        if (maior > maxLado) {
            val f = maxLado.toFloat() / maior
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        }

        val rotacao = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotacao != 0) {
            val m = Matrix().apply { postRotate(rotacao.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    fun paraBase64Jpeg(bmp: Bitmap, qualidade: Int = 75): String {
        val saida = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, qualidade, saida)
        return Base64.encodeToString(saida.toByteArray(), Base64.NO_WRAP)
    }
}
