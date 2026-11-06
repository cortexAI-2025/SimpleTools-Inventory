package com.simpletools.inventory

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import com.simpletools.inventory.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private val android.content.Context.preferences by preferencesDataStore("inventory")
private val languageKey = stringPreferencesKey("language")
private val themeKey = stringPreferencesKey("theme")
private val alertsKey = stringPreferencesKey("alerts")
private val dates = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
private val LocaleUi = compositionLocalOf { true }
@Composable private fun t(fr: String, en: String) = if (LocaleUi.current) fr else en

class InventoryViewModel(application: android.app.Application) : AndroidViewModel(application) {
    val repository = InventoryRepository(Room.databaseBuilder(application, InventoryDatabase::class.java, "inventory.db").build())
    val transfer = Transfer(repository)
    override fun onCleared() { repository.db.close() }
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<InventoryViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { InventoryApp(model) }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Sessions : Screen
    data class Items(val filter: Int = 0) : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val item: Item, val new: Boolean, val copiedFrom: String? = null) : Screen
    data class Scan(val nfc: Boolean, val item: Item? = null, val session: String? = null) : Screen
    data class Counting(val session: Session, val focus: String? = null) : Screen
}

private enum class Export(val extension: String, val mime: String) {
    CSV("csv", "text/csv"), JSON("json", "application/json"), PDF("pdf", "application/pdf"),
    BACKUP("json", "application/json")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryApp(model: InventoryViewModel) {
    val context = requireNotNull(LocalActivity.current as? ComponentActivity)
    val prefs by context.preferences.data.collectAsStateWithLifecycle(initialValue = null)
    val french = prefs?.get(languageKey) != "en"
    val dark = when (prefs?.get(themeKey)) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val repo = model.repository
    val dao = repo.dao
    val labels by remember { dao.labels() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var exports by remember { mutableStateOf(false) }
    var reportSession by rememberSaveable { mutableStateOf<String?>(null) }
    fun tr(fr: String, en: String) = if (french) fr else en

    fun run(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try { block(); onSuccess() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = when (e.message) {
                    "code" -> tr("Ce code est déjà associé à un article.", "This code already belongs to an item.")
                    "nfc" -> tr("Ce tag est déjà associé à un article.", "This tag already belongs to an item.")
                    "unit" -> tr("Ramenez le stock à zéro avant de changer l’unité.", "Set stock to zero before changing the unit.")
                    "changed" -> tr("Le stock a changé depuis le début du comptage. Créez une nouvelle session.", "Stock changed after counting started. Create a new session.")
                    "quantity" -> tr("Le stock doit rester positif ou nul.", "Stock must be zero or positive.")
                    else -> tr("Opération impossible. Vérifiez les valeurs, le fichier ou les conflits avec les données locales.",
                        "Could not complete the operation. Check values, the file, or conflicts with local data.")
                }
            } finally { busy = false }
        }
    }

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Export.CSV.mime)) { uri ->
        if (uri != null) run({ message = tr("Export enregistré.", "Export saved.") }) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { model.transfer.csv(it) } }
        }
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Export.JSON.mime)) { uri ->
        if (uri != null) run({ message = tr("Export enregistré.", "Export saved.") }) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { model.transfer.writeJson(it, false) } }
        }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Export.PDF.mime)) { uri ->
        val session = reportSession
        if (uri != null) run({ message = tr("PDF enregistré.", "PDF saved.") }) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { model.transfer.pdf(it, french, session) } }
        }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Export.BACKUP.mime)) { uri ->
        if (uri != null) run({ message = tr("Sauvegarde enregistrée.", "Backup saved.") }) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { model.transfer.writeJson(it, true) } }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run({ message = tr("Restauration effectuée.", "Backup restored.") }) {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { model.transfer.restore(it) } }
        }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        run { context.preferences.edit { it[alertsKey] = if (granted) "yes" else "no" } }
    }
    val low by remember { dao.low() }.collectAsStateWithLifecycle(0)
    val empty by remember { dao.empty() }.collectAsStateWithLifecycle(0)
    LaunchedEffect(low, empty, prefs?.get(alertsKey), french) {
        if (prefs?.get(alertsKey) == "yes" && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("stock", tr("Alertes stock", "Stock alerts"), NotificationManager.IMPORTANCE_LOW))
            if (low + empty == 0) manager.cancel(1)
            else manager.notify(1, NotificationCompat.Builder(context, "stock")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(tr("Alertes stock", "Stock alerts"))
                .setContentText(tr("Sous le seuil : $low · Épuisés : $empty", "Below threshold: $low · Out of stock: $empty"))
                .setOnlyAlertOnce(true).build())
        } else context.getSystemService(NotificationManager::class.java).cancel(1)
    }

    fun back() {
        screen = when (val current = screen) {
            is Screen.Scan -> current.item?.let { Screen.Detail(it.id) } ?: if (current.session != null) Screen.Sessions else Screen.Home
            is Screen.Edit -> if (current.new) Screen.Items() else Screen.Detail(current.item.id)
            is Screen.Detail -> Screen.Items()
            is Screen.Counting -> Screen.Sessions
            else -> Screen.Home
        }
        error = ""
    }
    BackHandler(screen != Screen.Home) { back() }
    CompositionLocalProvider(LocaleUi provides french) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text("SimpleTools Inventory") }, navigationIcon = {
                    if (screen != Screen.Home) TextButton(onClick = ::back) { Text("‹") }
                }, actions = {
                    TextButton(onClick = { screen = Screen.Settings }) { Text(t("Réglages", "Settings")) }
                })
            }) { inset ->
                Column(Modifier.padding(inset).fillMaxSize().padding(horizontal = 16.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (message.isNotEmpty()) TextButton(onClick = { message = "" }) { Text(message) }
                    when (val current = screen) {
                        Screen.Home -> Dashboard(dao, labels, low, empty, { screen = it }, { exports = true })
                        is Screen.Items -> ItemsPage(dao, current.filter) { screen = Screen.Detail(it.id) }
                        is Screen.Detail -> {
                            val item by remember(current.id) { dao.observeItem(current.id) }.collectAsStateWithLifecycle(null)
                            item?.let { active ->
                                DetailPage(active, dao, { screen = it }, busy,
                                    delete = { run({ screen = Screen.Items() }) { repo.delete(active.id) } },
                                    archive = { run { repo.save(active.copy(archived = !active.archived)) } },
                                    move = { kind, amount, reason, note, done ->
                                        run(done) { repo.move(active.id, kind, scaled(amount), reason, note) }
                                    })
                            }
                        }
                        is Screen.Edit -> key(current.item.id) {
                            Editor(current, dao, labels, busy, photoError = { error = tr("Photo illisible.", "Could not read the photo.") }) { item, initial, photo, replaced ->
                                run({ screen = Screen.Detail(item.id) }) {
                                    repo.save(item, scaled(initial), photo, replaced)
                                }
                            }
                        }
                        is Screen.Scan -> ScanPage(current.nfc) { value ->
                            run {
                                val session = current.session
                                if (current.item != null) {
                                    repo.save(current.item.copy(nfc = value))
                                    screen = Screen.Detail(current.item.id)
                                } else {
                                    val item = if (current.nfc) dao.byNfc(value) else dao.byCode(value)
                                    screen = if (session != null) {
                                        val header = requireNotNull(dao.session(session))
                                        if (item == null || dao.sessionCounts(session).none { it.itemId == item.id }) {
                                            message = tr("Article absent de cette session.", "Item is absent from this session.")
                                            Screen.Counting(header)
                                        } else Screen.Counting(header, item.id)
                                    } else if (item != null) Screen.Detail(item.id)
                                    else Screen.Edit(if (current.nfc) Item(name = "", nfc = value) else Item(name = "", code = value), true)
                                }
                            }
                        }
                        Screen.Sessions -> SessionsPage(dao, { screen = Screen.Counting(it) }) { name ->
                            run { screen = Screen.Counting(repo.start(name)) }
                        }
                        is Screen.Counting -> CountingPage(current, dao, busy,
                            scan = { screen = Screen.Scan(false, session = current.session.id) },
                            save = { row, amount, done -> run(done) { repo.count(current.session.id, row.itemId, scaled(amount)) } },
                            close = { run { repo.finalize(current.session.id); screen = Screen.Counting(requireNotNull(dao.session(current.session.id))) } },
                            export = { reportSession = current.session.id; pdf.launch("inventaire-comptage.pdf") })
                        Screen.Settings -> SettingsPage(prefs?.get(languageKey), prefs?.get(themeKey), prefs?.get(alertsKey) == "yes",
                            set = { key, value -> run { context.preferences.edit { it[key] = value } } },
                            alert = {
                                if (it && Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else run { context.preferences.edit { settings -> settings[alertsKey] = if (it) "yes" else "no" } }
                            }, labels = labels,
                            add = { kind, name -> run {
                                require(name.isNotBlank() && name.trim().length <= 160)
                                dao.label(Label(kind, name.trim()))
                            } },
                            remove = { label -> run {
                                require(dao.items().none { if (label.kind == "category") it.category == label.name else it.location == label.name })
                                dao.deleteLabel(label.kind, label.name)
                            } },
                            backup = { backup.launch("inventaire-sauvegarde.json") },
                            restore = { restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) })
                    }
                }
            }
            if (exports) AlertDialog(onDismissRequest = { exports = false }, title = { Text(t("Exporter", "Export")) },
                text = {
                    Column {
                        TextButton(onClick = { exports = false; csv.launch("inventaire.csv") }) { Text("CSV") }
                        TextButton(onClick = { exports = false; json.launch("inventaire.json") }) { Text("JSON") }
                        TextButton(onClick = { exports = false; reportSession = null; pdf.launch("inventaire.pdf") }) { Text("PDF") }
                    }
                }, confirmButton = { TextButton(onClick = { exports = false }) { Text(t("Fermer", "Close")) } })
        }
    }
}

@Composable private fun Dashboard(dao: InventoryDao, labels: List<Label>, low: Int, empty: Int, open: (Screen) -> Unit, export: () -> Unit) {
    val references by remember { dao.references() }.collectAsStateWithLifecycle(0)
    val totals by remember { dao.totals() }.collectAsStateWithLifecycle(emptyList())
    val recent by remember { dao.recent() }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("by AIWORKPAY", style = MaterialTheme.typography.labelLarge)
        Text(t("Tableau de bord", "Dashboard"), style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(t("Références : $references", "Items: $references"))
                Text(t("Catégories : ${labels.count { it.kind == "category" }}", "Categories: ${labels.count { it.kind == "category" }}"))
                Text(t("Quantités par unité", "Totals by unit"))
                totals.forEach { Text("${decimal(it.quantity)} ${it.unit}") }
                TextButton(onClick = { open(Screen.Items(1)) }) { Text(t("Sous le seuil : $low", "Below threshold: $low")) }
                TextButton(onClick = { open(Screen.Items(2)) }) { Text(t("Épuisés : $empty", "Out of stock: $empty")) }
            }
        }
        Button(onClick = { open(Screen.Scan(false)) }) { Text(t("Scanner", "Scan barcode")) }
        OutlinedButton(onClick = { open(Screen.Scan(true)) }) { Text(t("Scanner NFC", "Scan NFC")) }
        val defaultUnit = t("pièce", "piece")
    Button(onClick = { open(Screen.Edit(Item(name = "", unit = defaultUnit), true)) }) {
            Text(t("Ajouter un article", "Add an item"))
        }
        OutlinedButton(onClick = { open(Screen.Items()) }) { Text(t("Inventaire", "Inventory")) }
        OutlinedButton(onClick = { open(Screen.Sessions) }) { Text(t("Comptage physique", "Physical count")) }
        OutlinedButton(onClick = export) { Text(t("Exporter", "Export")) }
        Text(t("Derniers mouvements", "Recent movements"), style = MaterialTheme.typography.titleMedium)
        recent.forEach { Text("${it.itemName} · ${decimal(it.delta)} · ${dates.format(Instant.ofEpochMilli(it.time))}") }
    }
}

@Composable private fun Field(value: String, onValue: (String) -> Unit, name: String, numeric: Boolean = false) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(name) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable private fun Choice(name: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(name) }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(index) })
            }
        }
    }
}

@Composable private fun ItemsPage(dao: InventoryDao, initialFilter: Int, open: (Item) -> Unit) {
    var term by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(initialFilter) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val result by remember(term, filter, sort, archived, page) { dao.search(term.trim(), filter, sort, archived, 101, page * 100) }
        .collectAsStateWithLifecycle(emptyList())
    val filters = listOf(t("Tous", "All"), t("Stock faible", "Low stock"), t("Stock nul", "Out of stock"))
    val sorts = listOf(t("Nom", "Name"), t("Quantité", "Quantity"), t("Modification", "Modified"))
    Field(term, { term = it; page = 0 }, t("Nom, référence, code, catégorie, emplacement", "Name, reference, code, category, location"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(filters[filter], filters) { filter = it; page = 0 }
        Choice(sorts[sort], sorts) { sort = it; page = 0 }
    }
    Row { Checkbox(archived, { archived = it; page = 0 }); Text(t("Archives", "Archived"), Modifier.padding(top = 12.dp)) }
    LazyColumn {
        items(result.take(100), key = { it.id }) { item ->
            TextButton(onClick = { open(item) }) {
                Text("${item.name} · ${decimal(item.quantity)} ${item.unit} · ${item.category} · ${item.location}")
            }
        }
        item {
            Row {
                if (page > 0) TextButton(onClick = { page-- }) { Text(t("Précédent", "Previous")) }
                if (result.size > 100) TextButton(onClick = { page++ }) { Text(t("Suivant", "Next")) }
            }
        }
    }
}

@Composable private fun PhotoPreview(bytes: ByteArray?) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) Image(bitmap, t("Photo de l’article", "Item photo"), Modifier.fillMaxWidth().height(170.dp))
}

@Composable private fun Editor(
    route: Screen.Edit, dao: InventoryDao, labels: List<Label>, busy: Boolean,
    photoError: () -> Unit, save: (Item, String, ByteArray?, Boolean) -> Unit
) {
    val item = route.item
    var name by rememberSaveable { mutableStateOf(item.name) }
    var description by rememberSaveable { mutableStateOf(item.description) }
    var category by rememberSaveable { mutableStateOf(item.category) }
    var location by rememberSaveable { mutableStateOf(item.location) }
    var reference by rememberSaveable { mutableStateOf(item.reference) }
    var code by rememberSaveable { mutableStateOf(item.code.orEmpty()) }
    var unit by rememberSaveable { mutableStateOf(item.unit) }
    var initial by rememberSaveable { mutableStateOf("0") }
    var threshold by rememberSaveable { mutableStateOf(decimal(item.threshold)) }
    var buy by rememberSaveable { mutableStateOf(item.buy?.let(::decimal).orEmpty()) }
    var sell by rememberSaveable { mutableStateOf(item.sell?.let(::decimal).orEmpty()) }
    var currency by rememberSaveable { mutableStateOf(item.currency) }
    var notes by rememberSaveable { mutableStateOf(item.notes) }
    var keepNfc by rememberSaveable { mutableStateOf(true) }
    var updated by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var captureName by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing by remember(item.id, route.copiedFrom) { dao.observePhoto(route.copiedFrom ?: item.id) }.collectAsStateWithLifecycle(null)
    fun load(uri: Uri, temporary: File? = null) {
        photoBusy = true
        scope.launch {
            try {
                photo = withContext(Dispatchers.IO) { jpeg(context, uri) }
                updated = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { photoError() }
            finally { temporary?.delete(); photoBusy = false }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) load(it) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = captureName?.let { File(File(context.cacheDir, "capture"), it) }
        captureName = null
        if (file != null) {
            if (success) load(FileProvider.getUriForFile(context, "com.simpletools.inventory.photos", file), file)
            else file.delete()
        }
    }
    val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) photoError()
        else {
            val folder = File(context.cacheDir, "capture").apply { mkdirs() }
            val file = File(folder, id() + ".jpg")
            captureName = file.name
            try { take.launch(FileProvider.getUriForFile(context, "com.simpletools.inventory.photos", file)) }
            catch (_: Exception) { file.delete(); captureName = null; photoError() }
        }
    }
    val shown = if (updated) photo else existing
    val categories = labels.filter { it.kind == "category" }.map { it.name }
    val locations = labels.filter { it.kind == "location" }.map { it.name }
    val units = listOf(t("pièce", "piece"), "kg", t("litre", "liter"), t("mètre", "meter"), t("lot", "batch"))
    Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (route.new) t("Nouvel article", "New item") else t("Modifier l’article", "Edit item"),
            style = MaterialTheme.typography.headlineSmall)
        Field(name, { name = it }, t("Nom", "Name"))
        Field(description, { description = it }, t("Description", "Description"))
        Choice(category.ifBlank { t("Catégorie", "Category") }, listOf(t("Aucune", "None")) + categories) {
            category = categories.getOrNull(it - 1).orEmpty()
        }
        Choice(location.ifBlank { t("Emplacement", "Location") }, listOf(t("Aucun", "None")) + locations) {
            location = locations.getOrNull(it - 1).orEmpty()
        }
        Field(reference, { reference = it }, t("Référence interne", "Internal reference"))
        Field(code, { code = it }, t("Code-barres ou QR", "Barcode or QR"))
        if (item.nfc != null) Row {
            Checkbox(keepNfc, { keepNfc = it })
            Text(t("Conserver l’association NFC", "Keep NFC association"), Modifier.padding(top = 12.dp))
        }
        Choice(t("Unités courantes", "Common units"), units) { unit = units[it] }
        Field(unit, { unit = it }, t("Unité ou unité personnalisée", "Unit or custom unit"))
        if (route.new) Field(initial, { initial = it }, t("Quantité initiale", "Starting quantity"), true)
        Field(threshold, { threshold = it }, t("Seuil minimum", "Minimum threshold"), true)
        Field(buy, { buy = it }, t("Prix d’achat", "Purchase price"), true)
        Field(sell, { sell = it }, t("Prix de vente", "Sale price"), true)
        Field(currency, { currency = it }, t("Devise (EUR, USD…)", "Currency (EUR, USD…)"))
        Field(notes, { notes = it }, t("Notes", "Notes"))
        PhotoPreview(shown)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !photoBusy, onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(t("Galerie", "Gallery")) }
            TextButton(enabled = !photoBusy, onClick = { photoPermission.launch(Manifest.permission.CAMERA) }) {
                Text(t("Photo", "Take photo"))
            }
            if (shown != null) TextButton(enabled = !photoBusy, onClick = { updated = true; photo = null }) {
                Text(t("Retirer", "Remove"))
            }
        }
        val invalid = t("Vérifiez les quantités et les prix.", "Check quantities and prices.")
        var validation by remember { mutableStateOf("") }
        if (validation.isNotEmpty()) Text(validation, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy && !photoBusy && name.isNotBlank(), onClick = {
            try {
                val edited = item.copy(name = name, description = description, category = category, location = location,
                    reference = reference, code = code, nfc = item.nfc.takeIf { keepNfc }, unit = unit.trim(), threshold = scaled(threshold),
                    buy = buy.takeIf { it.isNotBlank() }?.let(::scaled), sell = sell.takeIf { it.isNotBlank() }?.let(::scaled),
                    currency = currency.trim().uppercase(Locale.ROOT), notes = notes)
                validation = ""
                save(edited, initial, shown, updated || route.copiedFrom != null)
            } catch (_: Exception) { validation = invalid }
        }) { Text(t("Enregistrer", "Save")) }
    }
}

@Composable private fun DetailPage(
    item: Item, dao: InventoryDao, open: (Screen) -> Unit, busy: Boolean, delete: () -> Unit,
    archive: () -> Unit, move: (Kind, String, String, String, () -> Unit) -> Unit
) {
    val history by remember(item.id) { dao.history(item.id) }.collectAsStateWithLifecycle(emptyList())
    val photo by remember(item.id) { dao.observePhoto(item.id) }.collectAsStateWithLifecycle(null)
    var confirmDelete by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf<Kind?>(null) }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    fun choose(type: Kind) { amount = ""; reason = ""; note = ""; kind = type }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(item.name, style = MaterialTheme.typography.headlineMedium)
            Text("${decimal(item.quantity)} ${item.unit}", style = MaterialTheme.typography.headlineSmall)
            Text(t("Seuil : ${decimal(item.threshold)}", "Threshold: ${decimal(item.threshold)}"))
            listOf(item.reference, item.code.orEmpty(), item.category, item.location, item.description, item.notes)
                .filter { it.isNotBlank() }.forEach { Text(it) }
            item.buy?.let { Text(t("Achat : ${decimal(it)} ${item.currency}", "Cost: ${decimal(it)} ${item.currency}")) }
            item.sell?.let { Text(t("Vente : ${decimal(it)} ${item.currency}", "Sale: ${decimal(it)} ${item.currency}")) }
            if (item.nfc != null) Text(t("Tag NFC associé", "NFC tag associated"))
            if (item.archived) Text(t("Archivé", "Archived"))
            PhotoPreview(photo)
            if (!item.archived) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { choose(Kind.IN) }) { Text(t("Entrée", "Stock in")) }
                    TextButton(onClick = { choose(Kind.OUT) }) { Text(t("Sortie", "Stock out")) }
                    TextButton(onClick = { choose(Kind.ADJUST) }) { Text(t("Ajustement", "Adjust")) }
                }
            }
            TextButton(onClick = { open(Screen.Edit(item, false)) }) { Text(t("Modifier", "Edit")) }
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                open(Screen.Edit(item.copy(id = id(), code = null, nfc = null, quantity = 0, archived = false, created = now, modified = now), true, item.id))
            }) { Text(t("Dupliquer", "Duplicate")) }
            TextButton(onClick = { open(Screen.Scan(true, item)) }) { Text(t("Associer un tag NFC", "Link an NFC tag")) }
            TextButton(enabled = !busy, onClick = archive) {
                Text(if (item.archived) t("Désarchiver", "Unarchive") else t("Archiver", "Archive"))
            }
            TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text(t("Supprimer", "Delete")) }
            Text(t("Historique", "History"), style = MaterialTheme.typography.titleLarge)
        }
        items(history, key = { it.id }) { movement ->
            Text("${kindName(movement.kind)} · ${decimal(movement.delta)} · ${dates.format(Instant.ofEpochMilli(movement.time))}")
            if (movement.reason.isNotBlank()) Text(movement.reason)
            if (movement.note.isNotBlank()) Text(movement.note)
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(t("Supprimer cet article ?", "Delete this item?")) },
        text = { Text(t("La photo sera supprimée. Les mouvements resteront dans le journal.", "The photo will be removed. Movements will remain in the log.")) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text(t("Supprimer", "Delete")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("Annuler", "Cancel")) } })
    kind?.let { type ->
        AlertDialog(onDismissRequest = { if (!busy) kind = null }, title = { Text(kindName(type)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(amount, { amount = it }, t("Quantité", "Quantity"), true)
                if (type == Kind.ADJUST) Text(t("Saisissez un écart signé, par exemple -2.", "Enter a signed difference, for example -2."))
                Field(reason, { reason = it }, t("Motif", "Reason"))
                Field(note, { note = it }, t("Note", "Note"))
            }
        }, confirmButton = {
            TextButton(enabled = !busy && amount.isNotBlank(), onClick = { move(type, amount, reason, note) { kind = null } }) {
                Text(t("Valider", "Confirm"))
            }
        }, dismissButton = { TextButton(onClick = { if (!busy) kind = null }) { Text(t("Annuler", "Cancel")) } })
    }
}

@Composable private fun kindName(kind: Kind): String = when (kind) {
    Kind.IN -> t("Entrée", "Stock in")
    Kind.OUT -> t("Sortie", "Stock out")
    Kind.ADJUST -> t("Ajustement", "Adjustment")
    Kind.COUNT -> t("Inventaire correctif", "Count correction")
}

@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
@Composable private fun ScanPage(nfc: Boolean, found: (String) -> Unit) {
    val context = requireNotNull(LocalActivity.current as? ComponentActivity)
    val callback by rememberUpdatedState(found)
    var manual by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val french = LocaleUi.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!nfc) {
            CameraScanner(french, found)
            Field(manual, { manual = it }, t("Saisir un code", "Enter a code"))
            Button(enabled = manual.isNotBlank() && manual.length <= 2048, onClick = { found(manual.trim()) }) {
                Text(t("Rechercher", "Look up"))
            }
        } else {
            val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
            var status by remember { mutableStateOf(nfcStatus(adapter)) }
            DisposableEffect(adapter, context) {
                val active = AtomicBoolean(false)
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            status = nfcStatus(adapter)
                            if (status == NfcStatus.READY) {
                                active.set(true)
                                adapter?.enableReaderMode(context, { tag ->
                                    val result = runCatching { TagIdentity.read(tag) }.getOrNull()
                                    context.runOnUiThread {
                                        if (active.get()) {
                                            if (result == null) invalid = true
                                            else callback(result)
                                        }
                                    }
                                }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
                            }
                        }
                        Lifecycle.Event.ON_PAUSE -> if (active.getAndSet(false)) adapter?.disableReaderMode(context)
                        else -> Unit
                    }
                }
                context.lifecycle.addObserver(observer)
                onDispose {
                    context.lifecycle.removeObserver(observer)
                    if (active.getAndSet(false)) adapter?.disableReaderMode(context)
                }
            }
            Text(when (status) {
                NfcStatus.ABSENT -> t("Cet appareil ne possède pas le NFC.", "This device has no NFC hardware.")
                NfcStatus.DISABLED -> t("Le NFC est désactivé.", "NFC is disabled.")
                NfcStatus.READY -> t("Approchez un tag NFC compatible.", "Hold a compatible NFC tag near the phone.")
            })
            if (status == NfcStatus.DISABLED) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }) { Text(t("Réglages NFC", "NFC settings")) }
            if (invalid) Text(t("Identifiant stable introuvable. Essayez un tag NDEF compatible.", "No stable identifier found. Try a compatible NDEF tag."))
        }
    }
}

@Composable private fun SessionsPage(dao: InventoryDao, open: (Session) -> Unit, create: (String) -> Unit) {
    val sessions by remember { dao.sessions() }.collectAsStateWithLifecycle(emptyList())
    var name by rememberSaveable { mutableStateOf("") }
    Field(name, { name = it }, t("Nom du comptage", "Count name"))
    Button(enabled = name.isNotBlank(), onClick = { create(name) }) { Text(t("Nouvelle session", "New session")) }
    LazyColumn {
        items(sessions, key = { it.id }) {
            TextButton(onClick = { open(it) }) {
                Text(it.name + " · " + if (it.closed == null) t("Reprendre", "Resume") else t("Validé", "Closed"))
            }
        }
    }
}

@Composable private fun CountingPage(
    route: Screen.Counting, dao: InventoryDao, busy: Boolean, scan: () -> Unit,
    save: (Count, String, () -> Unit) -> Unit, close: () -> Unit, export: () -> Unit
) {
    val session = route.session
    val rows by remember(session.id) { dao.counts(session.id) }.collectAsStateWithLifecycle(emptyList())
    var selected by remember(route.focus) { mutableStateOf(route.focus) }
    var amount by remember(route.focus) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val counted = rows.count { it.actual != null }
    Text(session.name, style = MaterialTheme.typography.headlineSmall)
    Text(t("$counted comptés · ${rows.size - counted} non comptés", "$counted counted · ${rows.size - counted} uncounted"))
    if (session.closed == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = scan) { Text(t("Scanner", "Scan")) }
            Button(enabled = counted > 0 && !busy, onClick = { confirm = true }) { Text(t("Valider", "Finalize")) }
        }
        Text(t("Revenez en arrière pour mettre le comptage en pause.", "Go back to pause this count."))
    }
    TextButton(onClick = export) { Text(t("Rapport PDF", "PDF report")) }
    LazyColumn {
        items(rows, key = { it.itemId }) { row ->
            val actual = row.actual
            val result = if (actual == null) t("Non compté", "Uncounted") else "${decimal(actual)} · Δ ${decimal(actual - row.expected)}"
            TextButton(enabled = session.closed == null, onClick = { selected = row.itemId; amount = actual?.let(::decimal).orEmpty() }) {
                Text("${row.name} · ${decimal(row.expected)} ${row.unit} · $result")
            }
        }
    }
    rows.find { it.itemId == selected }?.let { row ->
        AlertDialog(onDismissRequest = { if (!busy) selected = null }, title = { Text(row.name) },
            text = { Field(amount, { amount = it }, t("Quantité comptée", "Counted quantity"), true) },
            confirmButton = {
                TextButton(enabled = !busy && amount.isNotBlank(), onClick = { save(row, amount) { selected = null } }) {
                    Text(t("Enregistrer", "Save"))
                }
            }, dismissButton = { TextButton(onClick = { if (!busy) selected = null }) { Text(t("Annuler", "Cancel")) } })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(t("Valider les corrections ?", "Apply stock corrections?")) },
        text = { Text(t("Les articles non comptés seront ignorés. Les corrections seront ajoutées au journal.",
            "Uncounted items will be skipped. Corrections will be added to the log.")) },
        confirmButton = { TextButton(onClick = { confirm = false; close() }) { Text(t("Valider", "Confirm")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(t("Annuler", "Cancel")) } })
}

@Composable private fun SettingsPage(
    language: String?, theme: String?, enabled: Boolean,
    set: (androidx.datastore.preferences.core.Preferences.Key<String>, String) -> Unit,
    alert: (Boolean) -> Unit, labels: List<Label>, add: (String, String) -> Unit,
    remove: (Label) -> Unit, backup: () -> Unit, restore: () -> Unit
) {
    var label by rememberSaveable { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    val themes = listOf(t("Système", "System"), t("Clair", "Light"), t("Sombre", "Dark"))
    val codes = listOf("system", "light", "dark")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(t("Réglages", "Settings"), style = MaterialTheme.typography.headlineSmall)
            Choice(if (language == "en") "English" else "Français", listOf("Français", "English")) {
                set(languageKey, if (it == 0) "fr" else "en")
            }
            Choice(themes[codes.indexOf(theme).coerceAtLeast(0)], themes) { set(themeKey, codes[it]) }
            Row { Checkbox(enabled, alert); Text(t("Notifications de stock", "Stock notifications"), Modifier.padding(top = 12.dp)) }
            OutlinedButton(onClick = backup) { Text(t("Créer une sauvegarde complète", "Create a complete backup")) }
            OutlinedButton(onClick = { confirmRestore = true }) { Text(t("Restaurer une sauvegarde", "Restore a backup")) }
            Text(t("Catégories et emplacements", "Categories and locations"), style = MaterialTheme.typography.titleLarge)
            Field(label, { label = it }, t("Nom", "Name"))
            TextButton(enabled = label.isNotBlank(), onClick = { add("category", label); label = "" }) { Text(t("Créer une catégorie", "Create category")) }
            TextButton(enabled = label.isNotBlank(), onClick = { add("location", label); label = "" }) { Text(t("Créer un emplacement", "Create location")) }
        }
        items(labels, key = { it.kind + ":" + it.name }) {
            Row {
                Text(it.name + " · " + if (it.kind == "category") t("Catégorie", "Category") else t("Emplacement", "Location"),
                    Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { remove(it) }) { Text(t("Retirer", "Remove")) }
            }
        }
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false },
        title = { Text(t("Restaurer une sauvegarde ?", "Restore a backup?")) },
        text = { Text(t("Les données sont ajoutées. Un conflit avec l’inventaire local bloque l’import entier.",
            "Data is added. A conflict with the local inventory blocks the entire import.")) },
        confirmButton = { TextButton(onClick = { confirmRestore = false; restore() }) { Text(t("Choisir le fichier", "Choose file")) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(t("Annuler", "Cancel")) } })
}
