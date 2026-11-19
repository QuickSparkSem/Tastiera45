package com.tastiera45.ime

import android.content.Context
import android.graphics.PointF
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

class SwipeDecoder(context: Context) {

    enum class LangMode {
        BILINGUAL, IT, EN;

        companion object {
            fun fromPref(raw: String?): LangMode = when (raw?.uppercase(Locale.ROOT)) {
                "IT" -> IT
                "EN" -> EN
                else -> BILINGUAL
            }
        }
    }

    private data class WordEntry(
        val display: String,
        val keySeq: String,
        val collapsedSeq: String,
        val lang: Int, // 1 = IT, 2 = EN, 3 = BOTH
        val baseWeight: Float
    )

    private val prefs = context.getSharedPreferences("tastiera45_prefs", Context.MODE_PRIVATE)
    private var langMode: LangMode = LangMode.fromPref(MenuPrefs.getString(prefs, "kb_lang_mode", "BILINGUAL"))

    private val entries: List<WordEntry>
    private val byFirstLetter: Map<Char, List<WordEntry>>
    private val byNormalizedExact: Map<String, List<WordEntry>>
    private val userFreq = mutableMapOf<String, Int>()

    init {
        loadUserWords()
        val map = LinkedHashMap<String, WordEntry>(3600)

        fun ingest(rawList: String, langFlag: Int) {
            val tokens = rawList.split(' ', '\n', '\t', ',')
            var rank = 0
            for (t in tokens) {
                val word = t.trim()
                if (word.isEmpty()) continue
                val keySeq = normalizeToKeys(word)
                if (keySeq.isEmpty() || !keySeq.all { it in 'a'..'z' }) continue
                val collapsed = collapseRepeats(keySeq)
                // Peso decrescente in base alla frequenza nel dizionario
                val freqBonus = (1.0f - (rank / 2200f)).coerceIn(0.15f, 1.0f)
                rank++

                val existing = map[word]
                if (existing != null) {
                    map[word] = existing.copy(
                        lang = existing.lang or langFlag,
                        baseWeight = maxOf(existing.baseWeight, freqBonus)
                    )
                } else {
                    map[word] = WordEntry(
                        display = word,
                        keySeq = keySeq,
                        collapsedSeq = collapsed,
                        lang = langFlag,
                        baseWeight = freqBonus
                    )
                }
            }
        }

        ingest(ITALIAN_WORDS, 1)
        ingest(ENGLISH_WORDS, 2)

        entries = map.values.toList()
        byFirstLetter = entries.groupBy { it.keySeq.first() }
        byNormalizedExact = entries.groupBy { it.keySeq }
    }

    fun setLanguageMode(mode: String) {
        langMode = LangMode.fromPref(mode)
    }

    fun getLanguageMode(): LangMode = langMode

    private fun isEntryAllowed(entry: WordEntry): Boolean = when (langMode) {
        LangMode.BILINGUAL -> true
        LangMode.IT -> (entry.lang and 1) != 0
        LangMode.EN -> (entry.lang and 2) != 0
    }

    private fun loadUserWords() {
        val raw = MenuPrefs.getString(prefs, "learned_words_v2", "") ?: ""
        if (raw.isEmpty()) return
        for (pair in raw.split(';')) {
            val idx = pair.lastIndexOf(':')
            if (idx > 0) {
                val w = pair.substring(0, idx)
                val c = pair.substring(idx + 1).toIntOrNull() ?: 1
                if (w.isNotEmpty()) userFreq[w] = c
            }
        }
    }

    fun learnWord(word: String, isIncognito: Boolean) {
        if (isIncognito) return
        val clean = word.trim().trimEnd('.', ',', '!', '?', ';', ':')
        if (clean.length < 2 || clean.length > 28) return
        val keys = normalizeToKeys(clean)
        if (keys.length < 2 || !keys.all { it in 'a'..'z' }) return

        val lower = clean.lowercase(Locale.ITALIAN)
        val nextCount = ((userFreq[lower] ?: 0) + 1).coerceAtMost(50)
        userFreq[lower] = nextCount

        val serialized = userFreq.entries
            .sortedByDescending { it.value }
            .take(400)
            .joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString("learned_words_v2", serialized).apply()
    }

    /**
     * Decodifica un gesto swipe usando:
     * 1) Prossimità inizio/fine con tolleranza ai tasti adiacenti sull'arco
     * 2) Campionamento geometrico equidistante (24 punti) rispetto alle coordinate reali dei tasti
     * 3) Rilevamento dei punti di svolta/angoli del tracciato
     * 4) Priorità bilingue (IT + EN) e vocaboli appresi dall'utente
     */
    fun decode(
        rawPoints: List<PointF>,
        letterCenters: Map<Char, PointF>,
        keyStep: Float
    ): List<String> {
        if (rawPoints.size < 2 || letterCenters.size < 10) return emptyList()

        val startPt = rawPoints.first()
        val endPt = rawPoints.last()
        val maxStartDist = keyStep * 1.45f
        val maxEndDist = keyStep * 1.55f

        val validStartChars = letterCenters.filter { (_, pt) ->
            hypot(pt.x - startPt.x, pt.y - startPt.y) <= maxStartDist
        }.keys
        if (validStartChars.isEmpty()) return emptyList()

        val validEndChars = letterCenters.filter { (_, pt) ->
            hypot(pt.x - endPt.x, pt.y - endPt.y) <= maxEndDist
        }.keys
        if (validEndChars.isEmpty()) return emptyList()

        val samples = 24
        val userResampled = resamplePath(rawPoints, samples)
        val userTurns = extractTurnPoints(rawPoints, keyStep * 0.45f)
        val userTotalLen = polylineLength(rawPoints).coerceAtLeast(keyStep * 0.5f)

        val candidatePool = mutableListOf<WordEntry>()
        for (ch in validStartChars) {
            val list = byFirstLetter[ch] ?: continue
            for (entry in list) {
                if (!isEntryAllowed(entry)) continue
                if (entry.keySeq.last() in validEndChars) {
                    candidatePool.add(entry)
                }
            }
        }

        // Aggiunge eventuali parole apprese dall'utente non presenti nel dizionario base
        for ((learnedWord, count) in userFreq) {
            val ks = normalizeToKeys(learnedWord)
            if (ks.length >= 2 && ks.first() in validStartChars && ks.last() in validEndChars) {
                if (candidatePool.none { it.display.equals(learnedWord, ignoreCase = true) }) {
                    candidatePool.add(
                        WordEntry(
                            display = learnedWord,
                            keySeq = ks,
                            collapsedSeq = collapseRepeats(ks),
                            lang = 3,
                            baseWeight = (0.55f + count * 0.04f).coerceAtMost(1.35f)
                        )
                    )
                }
            }
        }

        if (candidatePool.isEmpty()) return emptyList()

        data class Scored(val word: String, val score: Float)
        val scored = ArrayList<Scored>(candidatePool.size)

        for (entry in candidatePool) {
            val seq = entry.collapsedSeq
            val firstCenter = letterCenters[seq.first()] ?: continue
            val lastCenter = letterCenters[seq.last()] ?: continue

            val startDist = hypot(startPt.x - firstCenter.x, startPt.y - firstCenter.y) / keyStep
            val endDist = hypot(endPt.x - lastCenter.x, endPt.y - lastCenter.y) / keyStep

            val templatePts = ArrayList<PointF>(seq.length)
            var missingKey = false
            for (c in seq) {
                val p = letterCenters[c]
                if (p == null) {
                    missingKey = true
                    break
                }
                templatePts.add(p)
            }
            if (missingKey || templatePts.isEmpty()) continue

            val templateLen = polylineLength(templatePts)
            // Scarta candidati con lunghezza tracciato totalmente incompatibile
            if (seq.length >= 4 && templateLen > userTotalLen * 2.35f) continue
            if (userTotalLen > keyStep * 2.2f && seq.length == 1) continue

            val shapeDist = if (templatePts.size == 1) {
                (startDist + endDist) * 0.5f
            } else {
                val tmplResampled = resamplePath(templatePts, samples)
                var sum = 0f
                for (i in 0 until samples) {
                    sum += hypot(
                        userResampled[i].x - tmplResampled[i].x,
                        userResampled[i].y - tmplResampled[i].y
                    )
                }
                (sum / samples) / keyStep
            }

            // Bonus/penalità sui punti di svolta del dito
            var turnPenalty = 0f
            if (userTurns.isNotEmpty() && templatePts.size >= 3) {
                for (tp in userTurns) {
                    val nearestKeyDist = templatePts.minOf { kp -> hypot(tp.x - kp.x, tp.y - kp.y) } / keyStep
                    if (nearestKeyDist > 0.75f) {
                        turnPenalty += (nearestKeyDist - 0.75f) * 0.55f
                    }
                }
            }

            val learnedBoost = (userFreq[entry.display.lowercase(Locale.ITALIAN)] ?: 0) * 0.05f
            val priorBonus = (entry.baseWeight + learnedBoost).coerceAtMost(1.45f) * 0.62f

            val totalCost =
                startDist * 1.95f +
                endDist * 2.05f +
                shapeDist * 2.85f +
                turnPenalty -
                priorBonus

            if (totalCost < 4.2f) {
                scored.add(Scored(entry.display, totalCost))
            }
        }

        scored.sortBy { it.score }

        val out = ArrayList<String>(6)
        val seenLower = HashSet<String>(6)
        for (item in scored) {
            val low = item.word.lowercase(Locale.ITALIAN)
            if (seenLower.add(low)) {
                out.add(item.word)
                if (out.size >= 6) break
            }
        }
        return out
    }

    /**
     * Correzione ortografica e ripristino accenti per digitazione a tocco (es. "perche" -> "perché")
     */
    fun correctWord(typed: String): List<String> {
        val clean = typed.trim()
        if (clean.length < 2 || clean.length > 24) return emptyList()
        val norm = normalizeToKeys(clean)
        if (norm.length < 2 || !norm.all { it in 'a'..'z' }) return emptyList()

        val out = ArrayList<String>(5)
        val seen = HashSet<String>()

        // 1. Corrispondenza esatta senza accenti (es. perche -> perché, cosi -> così, citta -> città)
        byNormalizedExact[norm]?.filter { isEntryAllowed(it) }
            ?.sortedByDescending { it.baseWeight }
            ?.forEach { entry ->
                if (!entry.display.equals(clean, ignoreCase = true) && seen.add(entry.display.lowercase(Locale.ITALIAN))) {
                    out.add(entry.display)
                }
            }

        // 2. Vicini con distanza di Levenshtein <= 1 o 2
        val maxDist = if (norm.length <= 4) 1 else 2
        val candidates = entries.asSequence()
            .filter { isEntryAllowed(it) }
            .filter { abs(it.keySeq.length - norm.length) <= maxDist }
            .filter { it.keySeq.first() == norm.first() || it.keySeq.last() == norm.last() }
            .mapNotNull { entry ->
                val d = levenshtein(norm, entry.keySeq, maxDist)
                if (d in 1..maxDist) {
                    val score = d * 1.5f - entry.baseWeight
                    entry to score
                } else null
            }
            .sortedBy { it.second }
            .take(5)

        for ((entry, _) in candidates) {
            if (seen.add(entry.display.lowercase(Locale.ITALIAN))) {
                out.add(entry.display)
                if (out.size >= 5) break
            }
        }
        return out
    }

    private fun normalizeToKeys(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase(Locale.ITALIAN)) {
            when (ch) {
                'à', 'á', 'â', 'ä', 'ã', 'å' -> sb.append('a')
                'è', 'é', 'ê', 'ë' -> sb.append('e')
                'ì', 'í', 'î', 'ï' -> sb.append('i')
                'ò', 'ó', 'ô', 'ö', 'õ', 'ø' -> sb.append('o')
                'ù', 'ú', 'û', 'ü' -> sb.append('u')
                'ç' -> sb.append('c')
                'ñ' -> sb.append('n')
                in 'a'..'z' -> sb.append(ch)
                else -> {} // ignora apostrofi e trattini nella sequenza tasti
            }
        }
        return sb.toString()
    }

    private fun collapseRepeats(s: String): String {
        if (s.length <= 1) return s
        val sb = StringBuilder(s.length)
        var prev = '\u0000'
        for (c in s) {
            if (c != prev) {
                sb.append(c)
                prev = c
            }
        }
        return sb.toString()
    }

    private fun polylineLength(pts: List<PointF>): Float {
        var total = 0f
        for (i in 1 until pts.size) {
            total += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        return total
    }

    private fun resamplePath(pts: List<PointF>, count: Int): List<PointF> {
        if (pts.isEmpty()) return emptyList()
        if (pts.size == 1) return List(count) { pts[0] }

        val totalLen = polylineLength(pts)
        if (totalLen <= 0.001f) return List(count) { pts[0] }

        val interval = totalLen / (count - 1)
        val result = ArrayList<PointF>(count)
        result.add(pts.first())

        var accumulated = 0f
        var i = 1
        var prev = pts[0]
        while (i < pts.size && result.size < count - 1) {
            val cur = pts[i]
            val segLen = hypot(cur.x - prev.x, cur.y - prev.y)
            if (accumulated + segLen >= interval && segLen > 0.0001f) {
                val t = (interval - accumulated) / segLen
                val nx = prev.x + (cur.x - prev.x) * t
                val ny = prev.y + (cur.y - prev.y) * t
                val np = PointF(nx, ny)
                result.add(np)
                prev = np
                accumulated = 0f
            } else {
                accumulated += segLen
                prev = cur
                i++
            }
        }
        while (result.size < count) {
            result.add(pts.last())
        }
        return result
    }

    private fun extractTurnPoints(pts: List<PointF>, minSegDist: Float): List<PointF> {
        if (pts.size < 5) return emptyList()
        val simplified = mutableListOf(pts.first())
        for (i in 1 until pts.size - 1) {
            if (hypot(pts[i].x - simplified.last().x, pts[i].y - simplified.last().y) >= minSegDist) {
                simplified.add(pts[i])
            }
        }
        simplified.add(pts.last())
        if (simplified.size < 3) return emptyList()

        val turns = mutableListOf<PointF>()
        for (i in 1 until simplified.size - 1) {
            val a = simplified[i - 1]
            val b = simplified[i]
            val c = simplified[i + 1]
            val ang1 = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val ang2 = atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble())
            var diff = Math.toDegrees(abs(ang2 - ang1)).toFloat()
            if (diff > 180f) diff = 360f - diff
            if (diff >= 42f) {
                turns.add(b)
            }
        }
        return turns
    }

    private fun levenshtein(a: String, b: String, maxLimit: Int): Int {
        val n = a.length
        val m = b.length
        if (abs(n - m) > maxLimit) return maxLimit + 1
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxLimit) return maxLimit + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }

    companion object {
        private const val ITALIAN_WORDS = """
che non per una con sono della delle degli dello dei dal dalla dalle dagli nel nella nelle negli sul sulla sulle sugli
come anche più tutto tutti tutte questa questo questi queste quello quella quelli quelle loro mio mia miei mie tuo tua tuoi tue
suo sua suoi sue nostro nostra nostri nostre vostro vostra vostri vostre molto molti molte poco pochi poche tanto tanti tante
altro altri altra altre stesso stessa stessi stesse ogni qualche nessuno nessuna niente nulla qualcosa qualcuno qualcuna
sempre mai ancora già ora adesso subito dopo prima poi quindi allora perché però infatti invece comunque soprattutto
così bene male meglio peggio forte piano veloce lento nuovo nuova nuovi nuove vecchio vecchia vecchi vecchie
grande grandi piccolo piccola piccoli piccole bello bella belli belle brutto brutta brutti brutte buono buona buoni buone
cattivo cattiva cattivi cattive alto alta alti alte basso bassa bassi basse lungo lunga lunghi lunghe corto corta corti corte
vero vera veri vere falso falsa falsi false giusto giusta giusti giuste sbagliato sbagliata sbagliati sbagliate
facile facili difficile difficili possibile possibili impossibile impossibili importante importanti necessario necessaria
libero libera liberi libere pronto pronta pronti pronte aperto aperta aperti aperte chiuso chiusa chiusi chiuse
pieno piena pieni piene vuoto vuota vuoti vuote felice felici triste tristi stanco stanca stanchi stanche
ciao buongiorno buonasera buonanotte salve arrivederci grazie prego scusa scusami perdonami perfetto ottimo ok va bene
d'accordo certissimo certo certamente sicuramente probabilmente forse magari volentieri aiutami aiuto favore per favore
essere avere fare dire potere volere dovere sapere stare dare andare venire vedere sentire parlare prendere mettere
trovare lasciare portare tenere chiedere rispondere capire pensare credere sperare ricordare dimenticare conoscere
imparare insegnare studiare lavorare giocare dormire mangiare bere cucinare comprare pagare vendere spendere risparmiare
aprire chiudere accendere spegnere iniziare finire continuare fermare aspettare arrivare partire tornare entrare uscire
salire scendere correre camminare guidare viaggiare volare nuotare leggere scrivere disegnare guardare ascoltare
chiamare mandare inviare ricevere cercare scegliere provare cambiare migliorare peggiorare risolvere sistemare
costruire creare distruggere rompere aggiustare pulire lavare ordinare preparare organizzare decidere accettare rifiutare
ho hai ha abbiamo avete hanno avevo avevi aveva avevamo avevate avevano avrò avrai avrà avremo avrete avranno
sono sei è siamo siete sono ero eri era eravamo eravate erano sarò sarai sarà saremo sarete saranno stato stata stati state
faccio fai fa facciamo fate fanno facevo facevi faceva farò farai farà fatto fatta fatti fatte
dico dici dice diciamo dite dicono dicevo dirò dirai dirà detto detta detti dette
posso puoi può possiamo potete possono potevo potrò potrai potrà potuto
voglio vuoi vuole vogliamo volete vogliono volevo vorrò vorrai vorrà voluto
devo devi deve dobbiamo dovete devono dovevo dovrò dovrai dovrà dovuto
so sai sa sappiamo sapete sanno sapevo saprò saprai saprà saputo
sto stai sta stiamo state stanno stavo starò starai starà
do dai dà diamo date danno davo darò darai darà dato
vado vai va andiamo andate vanno andavo andrò andrai andrà andato andata andati andate
vengo vieni viene veniamo venite vengono venivo verrò verrai verrà venuto venuta venuti venute
vedo vedi vede vediamo vedete vedono vedevo vedrò vedrai vedrà visto vista visti viste
tempo anno anni giorno giorni oggi domani ieri stamattina stasera stanotte settimana settimane mese mesi
ora ore minuto minuti secondo secondi momento momenti volta volte fine inizio metà parte parti
uomo uomini donna donne persona persone gente bambino bambini bambina bambine ragazzo ragazzi ragazza ragazze
amico amici amica amiche famiglia padre madre papà mamma figlio figli figlia figlie fratello sorella nonno nonna
casa case stanza camere cucina bagno letto tavolo sedia porta finestra muro tetto giardino balcone
città paese paesi mondo terra mare montagna lago fiume cielo sole luna stella stelle pioggia vento neve
acqua fuoco aria luce ombra colore colori bianco nero rosso blu verde giallo arancione viola grigio marrone
cibo pane pasta pizza carne pesce frutta verdura dolce caffè tè latte vino birra zucchero sale olio
lavoro ufficio azienda riunione progetto problema problemi soluzione idea idee domanda domande risposta risposte
telefono cellulare schermo tastiera computer programma applicazione sistema messaggio messaggi email lettera
numero numeri parola parole frase frasi testo pagina libro libri giornale notizia notizie musica film foto video
macchina auto moto bici treno aereo autobus strada piazza ponte stazione aeroporto biglietto viaggio vacanza
soldi denaro euro prezzo costo conto banca carta regalo negozio mercato supermercato ospedale farmacia medico
scuola università classe lezione esame voto studente professore maestro lingua italiano inglese francese spagnolo tedesco
mano mani piede piedi testa occhi occhio bocca naso orecchio orecchie cuore corpo mente voce nome cognome
vita morte amore amicizia felicità paura rabbia calma pazienza forza coraggio fortuna speranza sogno sogni
verità bugia segreto motivo ragione causa effetto risultato scopo obiettivo successo errore errori rischio
sicurezza privacy incognito cursore mouse tasto tasti spazio invio cancella seleziona copia incolla taglia traduci
sinistra destra sopra sotto dentro fuori vicino lontano avanti indietro insieme solo soltanto almeno circa
quasi abbastanza troppo poco tanto quanto quale quali chi cui dove quando perché mentre finché sebbene
oppure ossia cioè dunque quindi perciò tuttavia nonostante ecco c'è ci sono c'era c'erano
anch'io anch'essa un'altra un'idea un'ora l'altro l'altra l'anno l'uomo l'acqua l'aria l'amore l'amico
dell'anno dell'uomo nell'aria all'inizio all'ora sull'altro dall'altra quest'anno quell'uomo
"""

        private const val ENGLISH_WORDS = """
the and that have for not with you this but his from they say her she will one all would there their what
out about who get which when make can like time just him know take people into year your good some could
them see other than then now look only come its over think also back after use two how our work first
well way even new want because any these give day most us are was were been being has had did does doing
having made said goes going gone went came coming taken took taking seen saw seeing known knew knowing
thought thinking found find finding gave given giving told tell telling asked ask asking worked working
called call calling tried try trying needed need needing felt feel feeling became become becoming left leave
put putting mean meant meaning kept keep keeping let begin began begun beginning seemed seem seeming helped
talk talking turned turn turning started start starting showed show showing heard hear hearing played play
running ran run moved move moving liked live lived living believed believe believing held hold holding
brought bring bringing happened happen happening wrote write writing written provided provide providing
sat sit sitting stood stand standing lost lose losing paid pay paying met meet meeting included include
continuing set sets setting learned learn learning changed change changing led lead leading understood understand
watched watch watching followed follow following stopped stop stopping created create creating spoke speak
spoken read reading allowed allow allowing added add adding spent spend spending grew grow growing opened
walk walking walked won win winning offered offer offering remembered remember remembering loved love loving
considered consider considering appeared appear appearing bought buy buying waited wait waiting served serve
died die dying sent send sending expected expect expecting built build building stayed stay staying fell
fall falling cut cutting reached reach reaching killed kill killing remained remain remaining suggested
raised raise raising passed pass passing sold sell selling required require requiring reported report
decided decide deciding pulled pull pulling
hello hi hey morning afternoon evening night goodbye bye thanks thank please sorry excuse welcome okay
yes yeah sure maybe probably certainly definitely actually basically literally honestly seriously really
always never sometimes often usually rarely already yet still soon later today tomorrow yesterday tonight
week weeks month months year years hour hours minute minutes second seconds moment forever early late
here there everywhere somewhere anywhere nowhere inside outside above below under over between through
before after during while until since without against toward around across behind beside near far away
home house room kitchen bedroom bathroom door window wall floor roof garden street city town country
world earth water fire air light dark shadow sun moon star stars sky rain wind snow weather cold hot warm
color colors white black red blue green yellow orange purple pink brown gray grey
food bread pasta pizza meat fish fruit vegetable sweet coffee tea milk water wine beer sugar salt oil
money cash price cost bill bank card gift shop store market hospital pharmacy doctor health sick pain
school university class lesson exam grade student teacher language english italian french spanish german
phone mobile screen keyboard computer laptop software app application system message email letter note
number numbers word words sentence text page book books news music movie film photo picture video game
car bus train plane bike road square bridge station airport ticket trip travel vacation holiday
man men woman women person people child children kid kids boy girl friend friends family father mother
dad mom son daughter brother sister husband wife baby neighbor boss team group
hand hands foot feet head eye eyes mouth nose ear ears heart body mind voice name age life love
happy sad angry tired excited scared nervous calm brave strong weak fast slow big small large little
tall short high low long new old young good bad great awesome terrible nice fine pretty beautiful ugly
true false right wrong easy hard difficult simple complex possible impossible important ready open closed
full empty free busy clean dirty rich poor safe dangerous private public incognito cursor mouse click
select copy paste delete space enter shift translate settings menu search send done next back close
don't can't won't didn't doesn't haven't hasn't hadn't wouldn't couldn't shouldn't isn't aren't wasn't weren't
i'm you're he's she's it's we're they're i've you've we've they've i'll you'll he'll she'll we'll they'll
i'd you'd he'd she'd we'd they'd that's there's here's what's who's where's when's why's how's let's
"""
    }
}
