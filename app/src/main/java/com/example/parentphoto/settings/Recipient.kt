package com.example.parentphoto.settings

/** 送り先(ds 8.1)。idはUUIDで作成時に採番し、再利用しない。 */
data class Recipient(
    val id: String,
    val name: String,
    val email: String,
)
