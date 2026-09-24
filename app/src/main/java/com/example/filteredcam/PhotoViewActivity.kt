package com.example.filteredcam

import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class PhotoViewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_view)

        val uriString = intent.getStringExtra("photoUri")
        val imageView = findViewById<ImageView>(R.id.fullPhoto)

        uriString?.let {
            imageView.setImageURI(Uri.parse(it))
        }
    }
}