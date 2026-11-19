package com.tastiera45.ime

import android.content.Context
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class SwipeDecoder(private val context: Context) {

    private data class WordEntry(val word: String, val clean: String, val weight: Float)

    private val entries = mutableListOf<WordEntry>()
    private val knownWords = HashSet<String>()
    private val learnedPrefs by lazy {
        context.getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
    }

    init {
        loadDictionary()
        loadLearnedWords()
    }

    fun learnWord(rawWord: String, isIncognito: Boolean) {
        if (isIncognito) return
        val w = rawWord.trim().lowercase()
        val clean = normalizeWord(w)
        if (clean.length < 2 || clean.length > 24) return
        if (knownWords.add(w)) {
            entries.add(WordEntry(w, clean, 1.35f))
            val saved = learnedPrefs.getString("learned_words_v1", "").orEmpty()
            val updated = (saved.split(",").filter { it.isNotBlank() } + w).distinct().takeLast(600)
            learnedPrefs.edit().putString("learned_words_v1", updated.joinToString(",")).apply()
        }
    }

    private fun loadLearnedWords() {
        val saved = learnedPrefs.getString("learned_words_v1", "").orEmpty()
        saved.split(",").map { it.trim().lowercase() }.filter { it.length >= 2 }.forEach { w ->
            val clean = normalizeWord(w)
            if (clean.length >= 2 && knownWords.add(w)) {
                entries.add(WordEntry(w, clean, 1.35f))
            }
        }
    }

    private fun normalizeWord(w: String): String {
        val sb = StringBuilder(w.length)
        for (ch in w.lowercase()) {
            val mapped = when (ch) {
                'à', 'á', 'â', 'ä' -> 'a'
                'è', 'é', 'ê', 'ë' -> 'e'
                'ì', 'í', 'î', 'ï' -> 'i'
                'ò', 'ó', 'ô', 'ö' -> 'o'
                'ù', 'ú', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'ñ' -> 'n'
                else -> ch
            }
            if (mapped in 'a'..'z') sb.append(mapped)
        }
        return sb.toString()
    }

    private fun loadDictionary() {
        // 1. Carica eventuale dizionario extra in assets/italian_words.txt se presente
        try {
            context.assets.open("italian_words.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    val w = line.trim().lowercase()
                    val clean = normalizeWord(w)
                    if (clean.length >= 2 && knownWords.add(w)) {
                        val freqBoost = if (idx < 1500) 1.25f else 1.0f
                        entries.add(WordEntry(w, clean, freqBoost))
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 2. Ampio vocabolario italiano integrato + parole internazionali comuni
        val coreWords = RAW_DICTIONARY.split(' ', '\n', '\t', ',', ';')
        coreWords.forEachIndexed { idx, raw ->
            val w = raw.trim().lowercase()
            val clean = normalizeWord(w)
            if (clean.length >= 2 && knownWords.add(w)) {
                val freqBoost = if (idx < 450) 1.25f else 1.05f
                entries.add(WordEntry(w, clean, freqBoost))
            }
        }
    }

    fun decode(
        rawPoints: List<PointF>,
        letterCenters: Map<Char, PointF>,
        @Suppress("UNUSED_PARAMETER") keyStep: Float
    ): List<String> {
        if (rawPoints.size < 2 || letterCenters.size < 10) return emptyList()

        // Normalizza tutte le coordinate dei tasti in [0..1] per rendere il calcolo
        // indipendente da scala, rotazione o layout (Arco o Pop-up)
        val minX = letterCenters.values.minOf { it.x }
        val maxX = letterCenters.values.maxOf { it.x }
        val minY = letterCenters.values.minOf { it.y }
        val maxY = letterCenters.values.maxOf { it.y }
        val spanX = (maxX - minX).coerceAtLeast(1f)
        val spanY = (maxY - minY).coerceAtLeast(1f)

        fun norm(p: PointF) = PointF((p.x - minX) / spanX, (p.y - minY) / spanY)

        val normCenters = letterCenters.mapValues { norm(it.value) }
        val normSwipe = resamplePath(rawPoints.map { norm(it) }, 28)
        if (normSwipe.size < 2) return emptyList()

        val startPt = normSwipe.first()
        val endPt = normSwipe.last()

        fun nearestChar(p: PointF): Char? =
            normCenters.minByOrNull { hypot(p.x - it.value.x, p.y - it.value.y) }?.key

        val startChar = nearestChar(startPt)
        val endChar = nearestChar(endPt)

        // Filtra i candidati compatibili con inizio/fine traccia
        val candidates = entries.filter { entry ->
            val firstPt = normCenters[entry.clean.first()] ?: return@filter false
            val lastPt = normCenters[entry.clean.last()] ?: return@filter false
            val dStart = hypot(startPt.x - firstPt.x, startPt.y - firstPt.y)
            val dEnd = hypot(endPt.x - lastPt.x, endPt.y - lastPt.y)
            (entry.clean.first() == startChar || dStart < 0.24f) &&
                    (entry.clean.last() == endChar || dEnd < 0.24f)
        }

        val pool = if (candidates.isNotEmpty()) candidates else entries

        val scored = pool.mapNotNull { entry ->
            val keyPts = entry.clean.mapNotNull { normCenters[it] }
            if (keyPts.size != entry.clean.length) return@mapNotNull null

            val idealPath = resamplePath(keyPts, 28)
            val dtw = dtwDistance(normSwipe, idealPath)

            val dStart = hypot(startPt.x - keyPts.first().x, startPt.y - keyPts.first().y)
            val dEnd = hypot(endPt.x - keyPts.last().x, endPt.y - keyPts.last().y)

            // Penalità per lunghezza troppo diversa rispetto al percorso tracciato
            val traceLen = pathLength(normSwipe)
            val idealLen = pathLength(keyPts)
            val lenPenalty = abs(traceLen - idealLen) * 0.35f

            val totalCost = (dtw + dStart * 1.4f + dEnd * 1.4f + lenPenalty) / entry.weight
            entry.word to totalCost
        }

        return scored
            .sortedBy { it.second }
            .distinctBy { it.first }
            .take(6)
            .map { it.first }
    }

    private fun pathLength(pts: List<PointF>): Float {
        var total = 0f
        for (i in 1 until pts.size) {
            total += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        return total
    }

    private fun resamplePath(pts: List<PointF>, targetCount: Int): List<PointF> {
        if (pts.isEmpty()) return emptyList()
        if (pts.size == 1) return List(targetCount) { pts.first() }
        val totalLen = pathLength(pts)
        if (totalLen < 1e-5f) return List(targetCount) { pts.first() }

        val step = totalLen / (targetCount - 1)
        val out = ArrayList<PointF>(targetCount)
        out.add(pts.first())

        var accum = 0f
        var i = 1
        var prev = pts[0]
        while (i < pts.size && out.size < targetCount - 1) {
            val curr = pts[i]
            val seg = hypot(curr.x - prev.x, curr.y - prev.y)
            if (accum + seg >= step && seg > 1e-6f) {
                val t = (step - accum) / seg
                val nx = prev.x + t * (curr.x - prev.x)
                val ny = prev.y + t * (curr.y - prev.y)
                val np = PointF(nx, ny)
                out.add(np)
                prev = np
                accum = 0f
            } else {
                accum += seg
                prev = curr
                i++
            }
        }
        while (out.size < targetCount) out.add(pts.last())
        return out
    }

    private fun dtwDistance(a: List<PointF>, b: List<PointF>): Float {
        val n = a.size
        val m = b.size
        val band = 6
        val inf = 1e6f
        val dp = Array(n + 1) { FloatArray(m + 1) { inf } }
        dp[0][0] = 0f

        for (i in 1..n) {
            val jStart = max(1, i - band)
            val jEnd = min(m, i + band)
            for (j in jStart..jEnd) {
                val cost = hypot(a[i - 1].x - b[j - 1].x, a[i - 1].y - b[j - 1].y)
                val bestPrev = min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1]))
                dp[i][j] = cost + bestPrev
            }
        }
        return dp[n][m] / max(n, m)
    }

    companion object {
        private const val RAW_DICTIONARY = """
            che non per con sono come mais più anche solo tutto tutti tutte questa questo questi queste
            quello quella quelli quelle dove quando perché allora sempre ancora molto poco troppo bene male
            ciao grazie prego scusa buongiorno buonasera buon pomeriggio notte oggi domani ieri adesso ora
            subito dopo prima mentre durante senza contro verso sopra sotto dentro fuori vicino lontano
            sempre mai spesso volte ogni qualche alcuni alcune nessuno niente nulla qualcosa qualcuno
            essere avere fare dire andare venire stare dare potere volere dovere sapere vedere sentire
            parlare chiedere rispondere pensare credere capire trovare cercare prendere portare lasciare
            mettere tenere aprire chiudere iniziare finire continuare cambiare provare usare lavorare
            giocare mangiare bere dormire uscire entrare tornare partire arrivare passare restare vivere
            morire nascere crescere imparare studiare leggere scrivere ascoltare guardare osservare
            comprare vendere pagare costare vincere perdere aiutare salvare scegliere decidere ricordare
            dimenticare spiegare raccontare mostrare offrire ricevere mandare inviare chiamare telefonare
            messaggio messaggi telefono cellulare schermo tastiera lettera lettere parola parole frase frasi
            testo immagine foto video audio musica voce microfono internet rete wifi dati batteria
            casa lavoro ufficio scuola università strada piazza città paese mondo tempo anno anni mese mesi
            settimana settimane giorno giorni mattina sera ore minuto minuti secondo secondi momento
            persona persone uomo uomini donna donne ragazzo ragazzi ragazza ragazze bambino bambini
            famiglia madre padre mamma papà figlio figlia fratello sorella nonno nonna zio zia amico amici
            amica amiche amore cuore vita morte salute corpo testa mano mani occhio occhi bocca viso
            acqua pane vino caffè latte frutta carne pesce pasta pizza dolce zucchero sale olio
            macchina auto treno aereo nave bici moto autobus biglietto viaggio vacanza mare montagna
            sole luna cielo terra fuoco aria vento pioggia neve caldo freddo estate inverno primavera autunno
            bianco nero rosso blu verde giallo grigio rosa viola marrone arancione chiaro scuro
            grande piccolo lungo corto alto basso largo stretto vecchio nuovo giovane antico moderno
            bello brutto buono cattivo facile difficile veloce lento forte debole pieno vuoto aperto chiuso
            primo secondo terzo quarto quinto ultimo prossimo scorso stesso diverso uguale simile
            mio mia miei mie tuo tua tuoi tue suo sua suoi sue nostro nostra nostri nostre vostro vostra
            loro chi cui quale quali quanto quanta quanti quante ecco già ormai quasi forse magari
            davvero proprio abbastanza piuttosto invece comunque altrimenti infatti quindi dunque perciò
            nel nella nello nei negli nelle dal dalla dallo dai dagli dalle sul sulla sullo sui sugli sulle
            col coi tra fra fin fino oltre tramite secondo circa presso attorno lungo
            sono sei siamo siete erano stato stata stati state sarò sarà saremo saranno sarei sarebbe
            ho hai ha abbiamo avete hanno avevo aveva avevano avuto avuta avuti avute avrò avrà avremo
            faccio fai fa facciamo fate fanno facevo faceva fatto fatta fatti fatte farò farà faremo
            dico dici dice diciamo dite dicono dicevo diceva detto detta detti dette dirò dirà diremo
            vado vai va andiamo andate vanno andavo andava andato andata andati andate andrò andrà
            vengo vieni viene veniamo venite vengono venivo veniva venuto venuta verranno verrò verrà
            sto stai sta stiamo state stanno stavo stava stato staremo starà staranno
            posso puoi può possiamo potete possono potevo poteva potuto potuta potranno potrò potrà
            voglio vuoi vuole vogliamo volete vogliono volevo voleva voluto voluta vorrò vorrà vorranno
            devo devi deve dobbiamo dovete devono dovevo doveva dovuto dovuta dovrò dovrà dovranno
            so sai sa sappiamo sapete sanno sapevo sapeva saputo saputa saprò saprà sapranno
            vedo vedi vede vediamo vedete vedono vedevo vedeva visto vista visti viste vedrò vedrà
            sento senti sente sentiamo sentite sentono sentivo sentiva sentito sentita sentirò sentirà
            parlo parli parla parliamo parlate parlano parlavo parlava parlato parlata parlerò parlerà
            penso pensi pensa pensiamo pensate pensano pensavo pensava pensato pensata penserò penserà
            credo credi crede crediamo credete credono credevo credeva creduto creduta crederò crederà
            capisco capisci capisce capiamo capite capiscono capivo capiva capito capita capirò capirà
            trovo trovi trova troviamo trovate trovano trovavo trovava trovato trovata troverò troverà
            cerco cerchi cerca cerchiamo cercate cercano cercavo cercava cercato cercata cercherò cercherà
            prendo prendi prende prendiamo prendete prendono prendevo prendeva preso presa presi prese
            metto metti mette mettiamo mettete mettono mettevo metteva messo messa messi messe
            guardo guardi guarda guardiamo guardate guardano guardavo guardava guardato guardata
            scrivo scrivi scrive scriviamo scrivete scrivono scrivevo scriveva scritto scritta scritti scritte
            leggo leggi legge leggiamo leggete leggono leggevo leggeva letto letta letti lette
            mando mandi manda mandiamo mandate mandano mandavo mandava mandato mandata manderò manderà
            chiamo chiami chiama chiamiamo chiamate chiamano chiamavo chiamava chiamato chiamata
            arriva arrivano arrivato arrivata arrivati arrivate partire partito partita partiti partite
            tornato tornata tornati tornate passato passata passati passate successo succede succederà
            bisogno problema problemi soluzione idea idee motivo ragione torto dubbio domanda risposta
            esempio caso modo senso tipo genere specie parte punto fine inizio centro lato fondo
            nome cognome numero indirizzo email password codice conto carta soldi euro prezzo sconto
            negozio mercato ristorante bar albergo ospedale farmacia banca posta chiesa parco museo
            libro libri giornale rivista film serie canzone gioco partita squadra sport calcio
            cane gatto cavallo uccello fiore pianta albero foglia bosco giardino campo
            tavolo sedia letto porta finestra muro pavimento tetto cucina bagno camera salotto
            vestito scarpe giacca maglia pantaloni cappello borsa chiave chiavi orologio occhiali
            felice contento triste arrabbiato stanco tranquillo nervoso preoccupato sicuro pronto libero
            occupato importante necessario possibile impossibile normale strano vero falso giusto sbagliato
            perfetto ottimo fantastico incredibile meraviglioso terribile grave serio semplice complesso
            speciale particolare generale pubblico privato nazionale internazionale locale digitale
            hello world thank you thanks please sorry yes okay good morning night today tomorrow
            meeting call mail link file update project team check send reply confirm
        """
    }
}
