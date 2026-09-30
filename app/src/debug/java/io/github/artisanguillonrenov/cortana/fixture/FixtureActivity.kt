package io.github.artisanguillonrenov.cortana.fixture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Plain-View screen with known targets: neutral, send (L2), pay (L3), text and password fields. */
class FixtureActivity : Activity() {
    var clicks = 0
    lateinit var counter: TextView
    lateinit var message: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        counter = TextView(this).apply { text = "Compteur : 0"; id = ID_COUNTER }
        message = EditText(this).apply { hint = "Message"; id = ID_MESSAGE }
        val password = EditText(this).apply { hint = "Mot de passe"; id = ID_PASSWORD; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val next = Button(this).apply { text = "Suivant"; id = ID_NEXT; setOnClickListener { clicks++; counter.text = "Compteur : $clicks" } }
        val send = Button(this).apply { text = "Envoyer"; id = ID_SEND }
        val pay = Button(this).apply { text = "Payer 9,99 €"; id = ID_PAY }
        listOf(counter, message, password, next, send, pay).forEach(root::addView)
        setContentView(root)
    }

    companion object {
        const val ID_COUNTER = 0x7f0f0001
        const val ID_MESSAGE = 0x7f0f0002
        const val ID_PASSWORD = 0x7f0f0003
        const val ID_NEXT = 0x7f0f0004
        const val ID_SEND = 0x7f0f0005
        const val ID_PAY = 0x7f0f0006
    }
}
