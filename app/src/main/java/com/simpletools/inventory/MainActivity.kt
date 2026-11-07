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
import androidx.lifecycle.viewModelScope
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
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private val android.content.Context.preferences by preferencesDataStore("inventory")
private val languageKey = stringPreferencesKey("language")
private val themeKey = stringPreferencesKey("theme")
private val alertsKey = stringPreferencesKey("alerts")
private val dates = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
private val LocaleUi = compositionLocalOf { true }
private val ProUi = compositionLocalOf { false }
@Composable private fun t(fr: String, en: String) = if (LocaleUi.current) fr else en

class InventoryViewModel(application: android.app.Application) : AndroidViewModel(application) {
    val license = PlayLicense(application, viewModelScope)
    val repository = InventoryRepository(Room.databaseBuilder(application, InventoryDatabase::class.java, "inventory.db")
        .addMigrations(MIGRATION_1_2).build()) { license.state.value.pro }
    val transfer = Transfer(repository)
    override fun onCleared() { license.close(); repository.db.close() }
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<InventoryViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { InventoryApp(model) }
    }
    override fun onResume() {
        super.onResume()
        model.license.refresh()
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Pro : Screen
    data object Analysis : Screen
    data object Journal : Screen
    data class Sessions(val inventory: String? = null) : Screen
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
    val play by model.license.state.collectAsStateWithLifecycle()
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
    var labelItemId by rememberSaveable { mutableStateOf<String?>(null) }
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
                    "label_limit" -> tr("Cette limite de catégories ou d’emplacements est atteinte. PRO la débloque.",
                        "The category or location limit has been reached. PRO unlocks it.")
                    "free_limit" -> tr("Le catalogue contient déjà au moins 50 fiches. PRO permet d’en créer davantage.",
                        "The catalog already contains at least 50 items. PRO allows you to create more.")
                    "pro" -> tr("Cette opération nécessite Inventory PRO.", "This action requires Inventory PRO.")
                    "reason" -> tr("Indiquez un motif pour la correction.", "Enter a reason for this correction.")
                    "csv" -> tr("CSV invalide. Vérifiez les colonnes et les doublons.", "Invalid CSV. Check columns and duplicates.")
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
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val added = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { model.transfer.importCsv(it) }
            }
            message = tr("$added fiches importées.", "$added items imported.")
        }
    }
    val labelPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val itemId = labelItemId
        if (uri != null && itemId != null) run({ message = tr("Étiquette enregistrée.", "Label saved.") }) {
            repo.requirePro()
            val item = repo.createLabelCode(itemId)
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { writeProductLabel(item, it) }
            }
        }
    }
    fun printLabel(itemId: String) {
        run {
            repo.requirePro()
            val item = repo.createLabelCode(itemId)
            val file = withContext(Dispatchers.IO) {
                File.createTempFile("label-", ".pdf", context.cacheDir).also { target ->
                    try { target.outputStream().use { writeProductLabel(item, it) } }
                    catch (error: Exception) { target.delete(); throw error }
                }
            }
            try {
                context.getSystemService(android.print.PrintManager::class.java)
                    .print(item.name, LabelPrintAdapter(file),
                        android.print.PrintAttributes.Builder().setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build())
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
    fun advanced(action: () -> Unit) {
        if (model.license.state.value.pro) action() else screen = Screen.Pro
    }

    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        run { context.preferences.edit { it[alertsKey] = if (granted) "yes" else "no" } }
    }
    val low by remember { dao.low() }.collectAsStateWithLifecycle(0)
    val empty by remember { dao.empty() }.collectAsStateWithLifecycle(0)
    LaunchedEffect(low, empty, prefs?.get(alertsKey), french, play.pro) {
        if (play.pro && prefs?.get(alertsKey) == "yes" && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
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
            is Screen.Scan -> current.item?.let { Screen.Detail(it.id) } ?: if (current.session != null) Screen.Sessions() else Screen.Home
            is Screen.Edit -> if (current.new) Screen.Items() else Screen.Detail(current.item.id)
            is Screen.Detail -> Screen.Items()
            is Screen.Counting -> Screen.Sessions(current.session.inventory)
            else -> Screen.Home
        }
        error = ""
    }
    BackHandler(screen != Screen.Home) { back() }
    CompositionLocalProvider(LocaleUi provides french, ProUi provides play.pro) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text("SimpleTools Inventory") }, navigationIcon = {
                    if (screen != Screen.Home) TextButton(onClick = ::back) { Text("‹") }
                }, actions = {
                    TextButton(onClick = { screen = Screen.Pro }) { Text(if (play.pro) "PRO ✓" else "PRO") }
                    TextButton(onClick = { screen = Screen.Settings }) { Text(t("Réglages", "Settings")) }
                })
            }) { inset ->
                Column(Modifier.padding(inset).fillMaxSize().padding(horizontal = 16.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (message.isNotEmpty()) TextButton(onClick = { message = "" }) { Text(message) }
                    when (val current = screen) {
                        Screen.Home -> Dashboard(dao, labels, low, empty, { screen = it }, { advanced { exports = true } })
                        Screen.Pro -> ProPage(play, { model.license.buy(context) }, { model.license.refresh(explicit = true) })
                        Screen.Analysis -> if (play.pro) AnalysisPage(dao) else ProPage(play,
                            { model.license.buy(context) }, { model.license.refresh(explicit = true) })
                        Screen.Journal -> JournalPage(dao)
                        is Screen.Items -> ItemsPage(dao, current.filter, labels) { screen = Screen.Detail(it.id) }
                        is Screen.Detail -> {
                            val item by remember(current.id) { dao.observeItem(current.id) }.collectAsStateWithLifecycle(null)
                            item?.let { active ->
                                DetailPage(active, dao, { screen = it }, busy,
                                    delete = { run({ screen = Screen.Items() }) { repo.delete(active.id) } },
                                    archive = { run { repo.save(active.copy(archived = !active.archived)) } },
                                    addCode = { code -> run { repo.addCode(active.id, code) } },
                                    removeCode = { code -> run { repo.removeCode(active.id, code) } },
                                    exportLabel = { advanced {
                                        labelItemId = active.id
                                        labelPdf.launch("etiquette.pdf")
                                    } },
                                    printLabel = { advanced { printLabel(active.id) } },
                                    move = { kind, amount, reason, note, done ->
                                        run(done) { repo.move(active.id, kind, scaled(amount), reason, note) }
                                    })
                            }
                        }
                        is Screen.Edit -> key(current.item.id) {
                            Editor(current, dao, labels, busy, photoError = { error = tr("Photo illisible.", "Could not read the photo.") }) { item, initial, photo, replaced ->
                                run({ screen = Screen.Detail(item.id) }) {
                                    repo.save(item, scaled(initial), photo, replaced, duplicate = current.copiedFrom != null)
                                }
                            }
                        }
                        is Screen.Scan -> ScanPage(current.nfc) { value ->
                            run {
                                val session = current.session
                                if (session != null) repo.requirePro()
                                if (current.item != null) {
                                    repo.save(current.item.copy(nfc = value))
                                    screen = Screen.Detail(current.item.id)
                                } else {
                                    val item = if (current.nfc) dao.byNfc(value) else dao.byCode(value)
                                    screen = if (session != null) {
                                        val header = requireNotNull(dao.session(session))
                                        if (item == null || dao.count(session, item.id) == null) {
                                            message = tr("Article absent de cette session.", "Item is absent from this session.")
                                            Screen.Counting(header)
                                        } else Screen.Counting(header, item.id)
                                    } else if (item != null) Screen.Detail(item.id)
                                    else Screen.Edit(if (current.nfc) Item(name = "", nfc = value) else Item(name = "", code = value), true)
                                }
                            }
                        }
                        is Screen.Sessions -> SessionsPage(dao, labels, current.inventory, { screen = Screen.Counting(it) }) { name, inventory ->
                            advanced { run { screen = Screen.Counting(repo.start(name, inventory)) } }
                        }
                        is Screen.Counting -> CountingPage(current, dao, busy,
                            scan = { advanced { screen = Screen.Scan(false, session = current.session.id) } },
                            save = { row, amount, done -> run(done) { repo.count(current.session.id, row.itemId, scaled(amount)) } },
                            close = { run { repo.finalize(current.session.id); screen = Screen.Counting(requireNotNull(dao.session(current.session.id))) } },
                            export = { advanced { reportSession = current.session.id; pdf.launch("inventaire-comptage.pdf") } })
                        Screen.Settings -> SettingsPage(prefs?.get(languageKey), prefs?.get(themeKey), prefs?.get(alertsKey) == "yes",
                            set = { key, value -> run { context.preferences.edit { it[key] = value } } },
                            alert = {
                                advanced {
                                    if (it && Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else run { context.preferences.edit { settings -> settings[alertsKey] = if (it) "yes" else "no" } }
                                }
                            }, labels = labels,
                            add = { kind, name -> run {
                                repo.addLabel(kind, name)
                            } },
                            remove = { label -> run {
                                if (label.kind == "inventory") repo.requirePro()
                                require(dao.items().none {
                                    val value = when (label.kind) {
                                        "category" -> it.category
                                        "location" -> it.location
                                        else -> it.inventory
                                    }
                                    value == label.name
                                })
                                dao.deleteLabel(label.kind, label.name)
                            } },
                            backup = { advanced { backup.launch("inventaire-sauvegarde.json") } },
                            restore = { advanced { restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) } },
                            importCsv = { advanced { importCsv.launch(arrayOf("text/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream")) } })
                    }
                }
            }
            if (exports && play.pro) AlertDialog(onDismissRequest = { exports = false }, title = { Text(t("Exporter", "Export")) },
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
    val pro = ProUi.current
    val size by remember { dao.catalogSize() }.collectAsStateWithLifecycle(0)
    val references by remember { dao.references() }.collectAsStateWithLifecycle(0)
    val totals by remember { dao.totals() }.collectAsStateWithLifecycle(emptyList())
    val recent by remember { dao.recent() }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("by AIWORKPAY", style = MaterialTheme.typography.labelLarge)
        Text(t("Tableau de bord", "Dashboard"), style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(t("Références actives : $references", "Active items: $references"))
                if (!pro) Text(t("$size / 50 fiches · FREE", "$size / 50 items · FREE"))
                Text(t("Catégories : ${labels.count { it.kind == "category" }}", "Categories: ${labels.count { it.kind == "category" }}"))
                Text(t("Quantités par unité", "Totals by unit"))
                totals.forEach { Text("${decimal(it.quantity)} ${it.unit}") }
                if (pro) TextButton(onClick = { open(Screen.Items(1)) }) { Text(t("Sous le seuil : $low", "Below threshold: $low")) }
                TextButton(onClick = { open(Screen.Items(2)) }) { Text(t("Épuisés : $empty", "Out of stock: $empty")) }
            }
        }
        Button(onClick = { open(Screen.Scan(false)) }) { Text(t("Scanner", "Scan barcode")) }
        OutlinedButton(onClick = { open(Screen.Scan(true)) }) { Text(t("Scanner NFC", "Scan NFC")) }
        val defaultUnit = t("pièce", "piece")
        Button(onClick = {
            open(if (pro || InventoryRepository.canCreateFree(size)) Screen.Edit(Item(name = "", unit = defaultUnit), true)
                else Screen.Pro)
        }) {
            Text(if (pro || InventoryRepository.canCreateFree(size)) t("Ajouter un article", "Add an item")
                else t("Débloquer plus de fiches", "Unlock more items"))
        }
        OutlinedButton(onClick = { open(Screen.Items()) }) { Text(t("Inventaire", "Inventory")) }
        OutlinedButton(onClick = { open(Screen.Sessions()) }) { Text(t("Comptage physique", "Physical count")) }
        OutlinedButton(onClick = { open(Screen.Analysis) }) { Text(t("Analyses PRO", "PRO analytics")) }
        OutlinedButton(onClick = export) { Text(t("Exporter · PRO", "Export · PRO")) }
        OutlinedButton(onClick = { open(Screen.Journal) }) { Text(t("Journal des mouvements", "Stock journal")) }
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

@Composable private fun ItemsPage(dao: InventoryDao, initialFilter: Int, labels: List<Label>, open: (Item) -> Unit) {
    var term by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(initialFilter) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pro = ProUi.current
    var inventory by rememberSaveable { mutableStateOf<String?>(null) }
    val effectiveFilter = if (!pro && filter == 1) 0 else filter
    val result by remember(term, effectiveFilter, sort, archived, page, inventory) {
        dao.search(term.trim(), effectiveFilter, sort, archived, 101, page * 100, inventory)
    }.collectAsStateWithLifecycle(emptyList())
    val filters = if (pro) listOf(0 to t("Tous", "All"), 1 to t("Stock faible", "Low stock"), 2 to t("Stock nul", "Out of stock"))
        else listOf(0 to t("Tous", "All"), 2 to t("Stock nul", "Out of stock"))
    InventoryChoice(labels, inventory) { inventory = it; page = 0 }
    val sorts = listOf(t("Nom", "Name"), t("Quantité", "Quantity"), t("Modification", "Modified"))
    Field(term, { term = it; page = 0 }, t("Nom, référence, code, catégorie, emplacement", "Name, reference, code, category, location"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(filters.first { it.first == effectiveFilter }.second, filters.map { it.second }) { filter = filters[it].first; page = 0 }
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
    var inventory by rememberSaveable { mutableStateOf(item.inventory) }
    var supplier by rememberSaveable { mutableStateOf(item.supplier) }
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
        if (ProUi.current) {
            val inventories = labels.filter { it.kind == "inventory" }.map { it.name }
            Choice(inventory.ifBlank { t("Inventaire principal", "Main inventory") },
                listOf(t("Inventaire principal", "Main inventory")) + inventories) { inventory = inventories.getOrNull(it - 1).orEmpty() }
            Field(supplier, { supplier = it }, t("Fournisseur (facultatif)", "Supplier (optional)"))
            Field(threshold, { threshold = it }, t("Seuil minimum", "Minimum threshold"), true)
        }
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
        val pro = ProUi.current
        Button(enabled = !busy && !photoBusy && name.isNotBlank(), onClick = {
            try {
                val edited = item.copy(name = name, description = description, category = category, location = location,
                    reference = reference, code = code, nfc = item.nfc.takeIf { keepNfc }, unit = unit.trim(), threshold = if (pro) scaled(threshold) else item.threshold,
                    inventory = if (pro) inventory else item.inventory, supplier = if (pro) supplier.trim() else item.supplier,
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
    archive: () -> Unit, addCode: (String) -> Unit, removeCode: (String) -> Unit,
    exportLabel: () -> Unit, printLabel: () -> Unit, move: (Kind, String, String, String, () -> Unit) -> Unit
) {
    val history by remember(item.id) { dao.history(item.id) }.collectAsStateWithLifecycle(emptyList())
    val photo by remember(item.id) { dao.observePhoto(item.id) }.collectAsStateWithLifecycle(null)
    val pro = ProUi.current
    val codes by remember(item.id) { dao.codes(item.id) }.collectAsStateWithLifecycle(emptyList())
    var alternate by rememberSaveable { mutableStateOf("") }
    var codeToDelete by remember { mutableStateOf<String?>(null) }
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
            if (pro) Text(t("Seuil : ${decimal(item.threshold)}", "Threshold: ${decimal(item.threshold)}"))
            listOf(item.reference, item.code.orEmpty(), item.category, item.location, item.inventory, item.supplier, item.description, item.notes)
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
                    if (pro) TextButton(onClick = { choose(Kind.ADJUST) }) { Text(t("Ajustement", "Adjust")) }
                }
            }
            TextButton(onClick = { open(Screen.Edit(item, false)) }) { Text(t("Modifier", "Edit")) }
            if (pro) TextButton(onClick = {
                val now = System.currentTimeMillis()
                open(Screen.Edit(item.copy(id = id(), code = null, nfc = null, quantity = 0, archived = false, created = now, modified = now), true, item.id))
            }) { Text(t("Dupliquer", "Duplicate")) }
            TextButton(onClick = { open(Screen.Scan(true, item)) }) { Text(t("Associer un tag NFC", "Link an NFC tag")) }
            if (pro || item.archived) TextButton(enabled = !busy, onClick = archive) {
                Text(if (item.archived) t("Désarchiver", "Unarchive") else t("Archiver", "Archive"))
            }
            codes.forEach { value ->
                Row {
                    Text(value, Modifier.weight(1f))
                    if (pro) TextButton(onClick = { codeToDelete = value }) { Text(t("Retirer", "Remove")) }
                }
            }
            if (pro) {
                Field(alternate, { alternate = it }, t("Code-barres supplémentaire", "Additional barcode"))
                TextButton(enabled = !busy && alternate.isNotBlank(), onClick = { addCode(alternate) }) {
                    Text(t("Associer le code", "Link barcode"))
                }
                if (item.code == null) Text(t("La première étiquette attribue automatiquement un code à la fiche.",
                    "The first label automatically assigns a code to the item."))
                TextButton(enabled = !busy, onClick = exportLabel) { Text(t("Étiquette PDF", "PDF label")) }
                TextButton(enabled = !busy, onClick = printLabel) { Text(t("Imprimer l’étiquette", "Print label")) }
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
    codeToDelete?.let { value ->
        AlertDialog(onDismissRequest = { codeToDelete = null }, title = { Text(t("Retirer ce code ?", "Remove this barcode?")) },
            text = { Text(value) }, confirmButton = {
                TextButton(onClick = { codeToDelete = null; removeCode(value) }) { Text(t("Retirer", "Remove")) }
            }, dismissButton = { TextButton(onClick = { codeToDelete = null }) { Text(t("Annuler", "Cancel")) } })
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

@Composable private fun SessionsPage(dao: InventoryDao, labels: List<Label>, initial: String?,
    open: (Session) -> Unit, create: (String, String?) -> Unit) {
    val sessions by remember { dao.sessions() }.collectAsStateWithLifecycle(emptyList())
    var name by rememberSaveable { mutableStateOf("") }
    Field(name, { name = it }, t("Nom du comptage", "Count name"))
    var inventory by rememberSaveable { mutableStateOf(initial) }
    if (ProUi.current) InventoryChoice(labels, inventory) { inventory = it }
    Button(enabled = name.isNotBlank() || !ProUi.current, onClick = { create(name, inventory) }) { Text(t("Nouvelle session", "New session")) }
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
    val editable = session.closed == null && ProUi.current
    if (editable) {
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
            TextButton(enabled = editable, onClick = { selected = row.itemId; amount = actual?.let(::decimal).orEmpty() }) {
                Text("${row.name} · ${decimal(row.expected)} ${row.unit} · $result")
            }
        }
    }
    rows.find { editable && it.itemId == selected }?.let { row ->
        AlertDialog(onDismissRequest = { if (!busy) selected = null }, title = { Text(row.name) },
            text = { Field(amount, { amount = it }, t("Quantité comptée", "Counted quantity"), true) },
            confirmButton = {
                TextButton(enabled = !busy && amount.isNotBlank(), onClick = { save(row, amount) { selected = null } }) {
                    Text(t("Enregistrer", "Save"))
                }
            }, dismissButton = { TextButton(onClick = { if (!busy) selected = null }) { Text(t("Annuler", "Cancel")) } })
    }
    if (confirm && editable) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(t("Valider les corrections ?", "Apply stock corrections?")) },
        text = { Text(t("Les articles non comptés seront ignorés. Les corrections seront ajoutées au journal.",
            "Uncounted items will be skipped. Corrections will be added to the log.")) },
        confirmButton = { TextButton(onClick = { confirm = false; close() }) { Text(t("Valider", "Confirm")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(t("Annuler", "Cancel")) } })
}

@Composable private fun SettingsPage(
    language: String?, theme: String?, enabled: Boolean,
    set: (androidx.datastore.preferences.core.Preferences.Key<String>, String) -> Unit,
    alert: (Boolean) -> Unit, labels: List<Label>, add: (String, String) -> Unit,
    remove: (Label) -> Unit, backup: () -> Unit, restore: () -> Unit, importCsv: () -> Unit
) {
    var label by rememberSaveable { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    var labelToRemove by remember { mutableStateOf<Label?>(null) }
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
            OutlinedButton(onClick = importCsv) { Text(t("Importer des articles CSV · PRO", "Import items from CSV · PRO")) }
            OutlinedButton(onClick = backup) { Text(t("Créer une sauvegarde complète", "Create a complete backup")) }
            OutlinedButton(onClick = { confirmRestore = true }) { Text(t("Restaurer une sauvegarde", "Restore a backup")) }
            Text(t("Catégories et emplacements", "Categories and locations"), style = MaterialTheme.typography.titleLarge)
            Field(label, { label = it }, t("Nom", "Name"))
            TextButton(enabled = label.isNotBlank(), onClick = { add("category", label); label = "" }) { Text(t("Créer une catégorie", "Create category")) }
            TextButton(enabled = label.isNotBlank(), onClick = { add("location", label); label = "" }) { Text(t("Créer un emplacement", "Create location")) }
            if (ProUi.current) TextButton(enabled = label.isNotBlank(), onClick = { add("inventory", label); label = "" }) {
                Text(t("Créer un inventaire", "Create inventory"))
            }
        }
        items(labels, key = { it.kind + ":" + it.name }) {
            Row {
                val kind = when (it.kind) {
                    "category" -> t("Catégorie", "Category")
                    "location" -> t("Emplacement", "Location")
                    else -> t("Inventaire", "Inventory")
                }
                Text(it.name + " · " + kind,
                    Modifier.weight(1f).padding(top = 12.dp))
                if (ProUi.current || it.kind != "inventory") TextButton(onClick = { labelToRemove = it }) { Text(t("Retirer", "Remove")) }
            }
        }
    }
    labelToRemove?.let { value ->
        AlertDialog(onDismissRequest = { labelToRemove = null }, title = { Text(t("Retirer ce libellé ?", "Remove this label?")) },
            text = { Text(value.name) }, confirmButton = {
                TextButton(onClick = { labelToRemove = null; remove(value) }) { Text(t("Retirer", "Remove")) }
            }, dismissButton = { TextButton(onClick = { labelToRemove = null }) { Text(t("Annuler", "Cancel")) } })
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false },
        title = { Text(t("Restaurer une sauvegarde ?", "Restore a backup?")) },
        text = { Text(t("Les données sont ajoutées. Un conflit avec l’inventaire local bloque l’import entier.",
            "Data is added. A conflict with the local inventory blocks the entire import.")) },
        confirmButton = { TextButton(onClick = { confirmRestore = false; restore() }) { Text(t("Choisir le fichier", "Choose file")) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(t("Annuler", "Cancel")) } })
}

@Composable private fun InventoryChoice(labels: List<Label>, selected: String?, select: (String?) -> Unit) {
    val all = t("Tous les inventaires", "All inventories")
    val main = t("Inventaire principal", "Main inventory")
    val names = (labels.filter { it.kind == "inventory" }.map { it.name } + listOfNotNull(selected))
        .filter { it.isNotBlank() }.distinct()
    if (names.isEmpty()) return
    Choice(if (selected == null) all else selected.ifBlank { main }, listOf(all, main) + names) {
        select(when (it) { 0 -> null; 1 -> ""; else -> names[it - 2] })
    }
}

@Composable private fun ProPage(play: PlayState, buy: () -> Unit, restore: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SimpleTools Inventory PRO", style = MaterialTheme.typography.headlineSmall)
        Text(if (play.pro) t("Licence PRO active", "PRO license active") else t("Licence FREE", "FREE license"))
        Text(t("Achat unique · accès à vie", "One-time purchase · lifetime access"))
        Text("FREE", style = MaterialTheme.typography.titleMedium)
        Text(t("50 fiches, photos, scanner, entrées et sorties, historique illimité et accès hors ligne.",
            "50 items, photos, scanner, stock in/out, unlimited history and offline access."))
        Text("PRO", style = MaterialTheme.typography.titleMedium)
        Text(t("Fiches, catégories et emplacements illimités, plusieurs inventaires, seuils, comptages, analyses, CSV, PDF, sauvegarde et étiquettes.",
            "Unlimited items, categories and locations, multiple inventories, thresholds, counts, analytics, CSV, PDF, backups and labels."))
        val status = when (play.status) {
            PlayStatus.LOADING -> t("Vérification Google Play…", "Checking Google Play…")
            PlayStatus.CONFIG -> t("L’achat est indisponible sur cette version.", "Purchases are unavailable in this build.")
            PlayStatus.UNAVAILABLE -> t("Google Play ou le produit est indisponible. Réessayez avec une connexion.",
                "Google Play or the product is unavailable. Try again when connected.")
            PlayStatus.PENDING -> t("Paiement en attente. PRO s’activera après confirmation.", "Payment pending. PRO will activate after confirmation.")
            PlayStatus.CANCELED -> t("Achat annulé.", "Purchase canceled.")
            PlayStatus.INVALID -> t("L’achat n’a pas pu être vérifié.", "The purchase could not be verified.")
            PlayStatus.NO_PURCHASE -> t("Aucun achat PRO trouvé sur ce compte Google Play.", "No PRO purchase found on this Google Play account.")
            PlayStatus.READY -> ""
        }
        if (status.isNotEmpty()) Text(status)
        if (!play.pro) Button(enabled = play.price != null &&
            play.status in setOf(PlayStatus.READY, PlayStatus.NO_PURCHASE, PlayStatus.CANCELED), onClick = buy) {
            Text(t("Débloquer PRO", "Unlock PRO") + (play.price?.let { " · $it" } ?: ""))
        }
        OutlinedButton(enabled = play.status != PlayStatus.LOADING && play.status != PlayStatus.CONFIG, onClick = restore) {
            Text(t("Restaurer mes achats", "Restore purchases"))
        }
    }
}

@Composable private fun JournalPage(dao: InventoryDao) {
    val movements by remember { dao.observeMovements() }.collectAsStateWithLifecycle(emptyList())
    Text(t("Journal des mouvements", "Stock journal"), style = MaterialTheme.typography.headlineSmall)
    if (movements.isEmpty()) Text(t("Aucun mouvement.", "No movements."))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(movements, key = { it.id }) { movement ->
            Text("${movement.itemName} · ${kindName(movement.kind)} · ${decimal(movement.delta)} ${movement.unit.orEmpty()}")
            Text(dates.format(Instant.ofEpochMilli(movement.time)), style = MaterialTheme.typography.bodySmall)
            if (ProUi.current) {
                if (movement.reason.isNotBlank()) Text(movement.reason)
                if (movement.note.isNotBlank()) Text(movement.note)
            }
        }
    }
}

@Composable private fun AnalysisPage(dao: InventoryDao) {
    val zone = remember { ZoneId.systemDefault() }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now(zone).toString()) }
    val month = YearMonth.parse(monthText)
    val articles by remember { dao.observeItems() }.collectAsStateWithLifecycle(emptyList())
    val movements by remember { dao.observeMovements() }.collectAsStateWithLifecycle(emptyList())
    val analysis by produceState<StockAnalysis?>(null, articles, movements, month) {
        value = withContext(Dispatchers.Default) { analyzeStock(articles, movements, month, zone) }
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("Analyses de stock", "Stock analytics"), style = MaterialTheme.typography.headlineSmall)
        analysis?.let { report ->
            Text(t("Valeur actuelle au prix d’achat", "Current value at purchase price"), style = MaterialTheme.typography.titleMedium)
            if (report.valuation.isEmpty()) Text(t("Aucun prix d’achat renseigné.", "No purchase prices entered."))
            report.valuation.forEach { (currency, value) -> Text("${value.stripTrailingZeros().toPlainString()} $currency") }
            if (report.unpriced > 0) Text(t("${report.unpriced} fiches sans prix d’achat.", "${report.unpriced} items without purchase prices."))
            Text(t("Répartition des fiches par catégorie", "Items by category"), style = MaterialTheme.typography.titleMedium)
            report.categories.entries.sortedByDescending { it.value }.forEach { (category, count) ->
                Text("${category.ifBlank { t("Sans catégorie", "Uncategorized") }} · $count")
            }
            Text(t("Variations mensuelles", "Monthly changes"), style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick = { monthText = month.minusMonths(1).toString() }) { Text("‹") }
                Text(monthText, Modifier.padding(12.dp))
                TextButton(onClick = { monthText = month.plusMonths(1).toString() }) { Text("›") }
            }
            if (report.variation.isEmpty()) Text(t("Aucun mouvement pour ce mois.", "No movements this month."))
            report.variation.forEach { (unit, changes) ->
                Text(unit.ifBlank { t("Unité non enregistrée (ancien journal)", "Unit not recorded (legacy log)") })
                val incoming = decimal(changes.incoming)
                val outgoing = decimal(changes.outgoing)
                val corrections = decimal(changes.corrections)
                val net = decimal(changes.incoming - changes.outgoing + changes.corrections)
                Text(t("Entrées $incoming · Sorties $outgoing · Corrections $corrections · Variation nette $net",
                    "In $incoming · Out $outgoing · Corrections $corrections · Net change $net"))
            }
            Text(t("Articles les plus mouvementés", "Most active items"), style = MaterialTheme.typography.titleMedium)
            report.activity.filter { it.second > 0 }.take(10).forEach { (name, count) -> Text("$name · $count") }
            Text(t("Faible rotation (0 ou 1 mouvement)", "Low turnover (0 or 1 movement)"), style = MaterialTheme.typography.titleMedium)
            report.activity.filter { it.second <= 1 }.sortedBy { it.second }.take(10).forEach { (name, count) -> Text("$name · $count") }
        }
    }
}
