package org.walleth.accounts

import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import kotlinx.android.synthetic.main.activity_import_key.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kethereum.bip39.dirtyPhraseToMnemonicWords
import org.kethereum.bip39.toKey
import org.kethereum.bip39.validate
import org.kethereum.bip39.wordlists.WORDLIST_ENGLISH
import org.kethereum.crypto.toECKeyPair
import org.kethereum.extensions.toHexString
import org.kethereum.model.PrivateKey
import org.kethereum.wallet.loadKeysFromWalletJsonString
import org.ligi.kaxt.setVisibility
import org.ligi.kaxtui.alert
import org.walleth.R
import org.walleth.activities.BaseSubActivity
import org.walleth.activities.ImportAsActivity
import org.walleth.data.*
import org.walleth.data.addressbook.AccountKeySpec
import org.walleth.khex.hexToByteArray
import org.walleth.qrscan.startScanActivityForResult
import java.io.FileNotFoundException

enum class KeyType {
    ECDSA, JSON, WORDLIST
}

private const val SERIALISATION_DELIMITER = "%%%"
fun getAccountSpec(value: String, type: KeyType): AccountKeySpec = AccountKeySpec(ACCOUNT_TYPE_IMPORT, initPayload = "$type%%%$value")

fun Context.getCreateImportIntentFor(value: String, type: KeyType) = getKeyImportIntentViaCreate(getAccountSpec(value, type))
fun Context.getKeyImportIntent(spec: AccountKeySpec) = Intent(this, ImportKeyActivity::class.java).apply {
    putExtra(EXTRA_KEY_ACCOUNTSPEC, spec)
}
fun Context.getKeyImportIntentViaCreate(spec: AccountKeySpec) = Intent(this, CreateAccountActivity::class.java).apply {
    putExtra(EXTRA_KEY_ACCOUNTSPEC, spec)
}


open class ImportKeyActivity : BaseSubActivity() {

    private var importing = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_import_key)

        var type = KeyType.WORDLIST.toString()
        intent.getParcelableExtra<AccountKeySpec>(EXTRA_KEY_ACCOUNTSPEC)?.let { spec ->

            spec.initPayload?.split(SERIALISATION_DELIMITER)?.let { params ->
                key_content.setText(params.last())
                type = params.first()


            }

        }

        type_wordlist_select.isChecked = KeyType.valueOf(type) == KeyType.WORDLIST
        type_json_select.isChecked = KeyType.valueOf(type) == KeyType.JSON
        type_ecdsa_select.isChecked = !type_json_select.isChecked && !type_wordlist_select.isChecked

        key_type_select.setOnCheckedChangeListener { _, _ ->
            refreshKeyTypeDependingUI()
        }

        supportActionBar?.subtitle = getString(R.string.import_json_subtitle)

        fab.setOnClickListener {
            doImport()
        }

        refreshKeyTypeDependingUI()
    }

    private fun refreshKeyTypeDependingUI() {
        password_container.setVisibility(!type_ecdsa_select.isChecked)
        key_container.hint = getString(if (type_wordlist_select.isChecked) {
            R.string.key_input_wordlist_hint
        } else {
            R.string.key_input_key_hint
        })
    }

    private fun doImport() = GlobalScope.launch(Dispatchers.Main) {
        if (importing) {
            return@launch
        }
        importing = true

        fab_progress_bar.visibility = View.VISIBLE
        try {

            val importKey = withContext(Dispatchers.Default) {
                val content = key_content.text.toString()
                when {
                    type_json_select.isChecked ->
                        content.loadKeysFromWalletJsonString(password.text.toString())
                    type_wordlist_select.isChecked -> {
                        val mnemonicWords = dirtyPhraseToMnemonicWords(content)
                        if (!mnemonicWords.validate(WORDLIST_ENGLISH)) {
                            throw IllegalArgumentException("Mnemonic phrase not valid")
                        }
                        mnemonicWords.toKey(DEFAULT_ETHEREUM_BIP44_PATH).keyPair

                    }
                    else -> PrivateKey(content.hexToByteArray()).toECKeyPair()
                }

            }

            if (importKey != null) {
                val initPayload = importKey.privateKey.key.toHexString() + "/" + importKey.publicKey.key.toHexString()
                val spec = AccountKeySpec(ACCOUNT_TYPE_IMPORT, initPayload = initPayload)

                val intent = Intent(this@ImportKeyActivity, ImportAsActivity::class.java).putExtra(EXTRA_KEY_ACCOUNTSPEC, spec)
                startActivityForResult(intent, REQUEST_CODE_IMPORT_AS)
            } else {
                AlertDialog.Builder(this@ImportKeyActivity).setMessage("Could not import key")
                        .setTitle(getString(R.string.dialog_title_error)).show()
            }

        } catch (e: Exception) {
            AlertDialog.Builder(this@ImportKeyActivity).setMessage(e.message)
                    .setTitle(getString(R.string.dialog_title_error)).show()
        }

        fab_progress_bar.visibility = View.INVISIBLE
        importing = false
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_import, menu)
        menu.findItem(R.id.menu_open).isVisible = Build.VERSION.SDK_INT >= 19
        return super.onCreateOptionsMenu(menu)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int,
                                         resultData: Intent?) {


        resultData?.let {
            if (it.hasExtra("SCAN_RESULT")) {
                key_content.setText(it.getStringExtra("SCAN_RESULT"))
            }
            if (requestCode == REQUEST_CODE_OPEN_DOCUMENT && resultCode == Activity.RESULT_OK) {

                it.data?.let { data ->
                    readTextFromUri(data)?.run {
                        if (length > 1_000) {
                            alert("The selected content does not look like a key. If you think it should be - please contact walleth@walleth.org ")
                        } else {
                            key_content.setText(this)
                        }
                    }
                }
            }

            if (requestCode == REQUEST_CODE_IMPORT_AS && resultCode == Activity.RESULT_OK) {
                setResult(resultCode, resultData)
                finish()
            }
        }
    }

    private fun readTextFromUri(uri: Uri) = try {
        contentResolver.openInputStream(uri)?.reader()?.readText()
    } catch (fileNotFoundException: FileNotFoundException) {
        alert("Cannot read from $uri - if you think I should - please contact walleth@walleth.org with details of the device (Android version,Brand) and the beginning of the uri")
        null
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {

        R.id.menu_open -> true.also {
            tryOpen()
        }

        R.id.menu_scan -> true.also {
            startScanActivityForResult(this)
        }

        else -> super.onOptionsItemSelected(item)
    }

    @TargetApi(19)
    private fun tryOpen() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"

            startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            alert(R.string.saf_activity_not_found_problem)
        }
    }
}
