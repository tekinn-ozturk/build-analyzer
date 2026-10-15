# Build Analyzer

> **Yapay zekâ asistanı / yeni oturum için not:** bu README, her dosyayı okumadan projeyi anlamak için tek doğru
> kaynaktır. Kodla elle senkron tutulur. Önce tamamını okuyun; tek tek dosyaları yalnızca değiştirmeniz
> gerektiğinde açın.

Türkçe konuşan bir QA ekibi (yaklaşık 4 kullanıcı, tek geliştirici) için yapay zekâ destekli **Jenkins build hata
analizcisi**. Kullanıcı bir **Jenkins build URL'si** yapıştırır; uygulama build'in konsol logunu çeker, **hangi
testlerin neden düştüğünü** bulur, bir LLM'den kısa, kanıta dayalı bir kök neden ve somut çözümler ister, sonucu
PostgreSQL'e kaydeder ve küçük bir web arayüzünde gösterir.

```
Jenkins Build URL → konsol logu → hatalar çıkarılır ve gruplanır → AI analizi → PostgreSQL → Analiz / Geçmiş
```

- **Bir build = bir analiz = bir veritabanı satırı**; ister 1 test düşsün ister 500.
- Normal durumda **build başına tek LLM çağrısı** — asla test başına bir çağrı yapılmaz. Birden fazla başarısız test
  önce hata imzasına göre gruplanır; gruplar tek prompt'a sığmazsa ancak o zaman birkaç çağrıya bölünür
  (varsayılan en fazla 3).
- **SUCCESS build'ler** LLM çağrılmadan anında cevaplanır.
- Hiçbir şey uydurulmaz: logda görünmeyen bir değer `null` kalır (tahmini dosya, satır veya test sayısı yok).
- Kullanıcı, proje, pipeline veya giriş (login) yok (bilerek kaldırıldı, bkz. [Veritabanı](#veritabanı)).

**Yol gösteren kural (proje sahibinin kararı): kod tabanı, tek bir geliştiricinin bir hatayı 10–15 dakikada baştan
sona takip edebileceği kadar küçük kalmalı.** Gerçek bir ihtiyaç olmadan yeni katman, genel soyutlama, ek endpoint,
bağımlılık veya "geleceğe hazırlık" yok; backend değişiklikleri önce proje sahibiyle konuşulur.

## İçindekiler
- [Teknolojiler](#teknolojiler)
- [Yerelde çalıştırma](#yerelde-çalıştırma)
- [Uygulamanın kullanımı](#uygulamanın-kullanımı)
- [Bir analiz nasıl çalışır](#bir-analiz-nasıl-çalışır)
- [API](#api)
- [Veritabanı](#veritabanı)
- [Frontend](#frontend)
- [Mimari](#mimari)
- [Paket / sınıf haritası](#paket--sınıf-haritası)
- [Yapılandırma](#yapılandırma-srcmainresourcesapplicationyml)
- [Testler](#testler)
- [Mimari kurallar](#mimari-kurallar-korunmalı)
- [Konvansiyonlar ve dikkat edilecekler](#konvansiyonlar-ve-dikkat-edilecekler)
- [Durum ve yol haritası](#durum-ve-yol-haritası)

## Teknolojiler
- **Backend:** Java 21, Spring Boot 3.3.5, Maven, Lombok; **Hexagonal (Ports & Adapters)**, LLM sağlayıcısından
  bağımsız.
- **Jenkins erişimi:** Spring `RestClient` (HTTP Basic auth, kimlik bilgileri ortam değişkenlerinden); yalnızca
  `consoleText` okunur.
- **LLM:** **OpenAI Responses API** (`POST /v1/responses`), varsayılan model **`gpt-5-mini`**, katı yapılandırılmış
  çıktı (JSON schema). Sağlayıcı `LlmProvider` port'unun arkasındadır; bugün tek implementasyonu OpenAI.
- **Veritabanı:** **PostgreSQL** (Spring Data JPA, şema **Flyway** ile) — tek tablo, `analyses`.
- **Frontend:** `frontend/` altında React 19 + TypeScript + Vite + MUI 9 + React Router 8 (yalın V1, state
  kütüphanesi yok).
- springdoc-openapi (Swagger UI).

## Yerelde çalıştırma

Gereksinimler: `http://localhost:8080` üzerinde bir Jenkins, bir **OpenAI API anahtarı**, yerel bir **PostgreSQL**
(veritabanı `build_analyzer`, kullanıcı `build_analyzer_user`; PostgreSQL 18 kullanılıyor) ve frontend için Node.js.
Backend **8090 portunu**, frontend geliştirme sunucusu **5173**'ü dinler.

**Günlük başlatma:** IntelliJ'de `BuildAnalyzerApplication`'ı çalıştırın (ortam değişkenleri aşağıda) → `cd frontend`
→ `npm run dev` → http://localhost:5173 adresini açın.

Gizli bilgiler **yalnızca ortam değişkenlerinden** gelir — asla `application.yml`'den değil:

| Ortam değişkeni | Zorunlu | Amaç |
|---|---|---|
| `OPENAI_API_KEY` | **evet** | OpenAI API anahtarı. Yoksa uygulama başlamaz (`OPENAI_API_KEY environment variable is not set`). |
| `JENKINS_USERNAME` | bu Jenkins için evet | anonim erişim 403 döner |
| `JENKINS_API_TOKEN` | bu Jenkins için evet | Jenkins → kullanıcı menüsü → *Security* → *API Token* |
| `DB_PASSWORD` | **evet** | PostgreSQL kullanıcısının şifresi |
| `DB_URL` | hayır | varsayılan `jdbc:postgresql://localhost:5432/build_analyzer` |
| `DB_USERNAME` | hayır | varsayılan `build_analyzer_user` |

- **IntelliJ (uygulama normalde böyle çalıştırılır):** Run configuration `BuildAnalyzerApplication` → *Edit
  Configurations…* → *Environment variables*:
  `OPENAI_API_KEY=<key>;JENKINS_USERNAME=<user>;JENKINS_API_TOKEN=<token>;DB_PASSWORD=<db password>` → uygulamayı
  yeniden başlatın (ortam değişkenleri yalnızca açılışta okunur).
- **İlk başlatma:** şemayı Flyway oluşturur; geçmiş boş başlar. Elle kurulması gereken bir şey yok.
- **Terminal (PowerShell):**
  ```powershell
  $env:OPENAI_API_KEY = "<key>"
  $env:JENKINS_USERNAME = "<user>"
  $env:JENKINS_API_TOKEN = "<token>"
  $env:DB_PASSWORD = "<db password>"
  mvn spring-boot:run
  ```
  (cmd.exe: `set "OPENAI_API_KEY=<key>"` vb.)
- **Frontend:**
  ```powershell
  cd frontend
  npm install
  npm run dev      # http://localhost:5173 — /api istekleri :8090'daki backend'e yönlendirilir (CORS gerekmez)
  ```

OpenAI anahtarı https://platform.openai.com/api-keys adresinden oluşturulur. Asla commit etmeyin.

**API'yi arayüz olmadan denemek:** http://localhost:8090/swagger-ui/index.html adresindeki Swagger UI →
`POST /api/v1/analysis/build` → *Try it out*; ya da Postman ile ham JSON gövdesiyle
`POST http://localhost:8090/api/v1/analysis/build`.

## Uygulamanın kullanımı
1. **Dashboard:** bir Jenkins build URL'si yapıştırın (ör. `https://jenkins.company.com/job/Team/job/UI-Test/125/`) →
   *Analyze Build*. URL anında kontrol edilir (her derinlikteki klasörlerin içindeki job'lar sorunsuz). Senkron
   istek sürerken (genelde 3–15 sn) arayüz "Build analiz ediliyor..." gösterir. Son analizler aşağıda listelenir.
2. **Analiz detayı** (`/analyses/:id`): build numarası, durum etiketi, job, analiz zamanı, "Jenkins'te aç" bağlantısı
   ve — logda test özeti varsa — "Testler: 30 toplam · 20 başarılı · 10 başarısız · 0 atlanan".
   İki sekme:
   - **AI Analysis** — analizin bulduğu şey (aşağıdaki üç duruma bakın).
   - **Relevant Logs** — filtrelenmiş log kesiti / son 200 ham satır; logun tamamı Jenkins'te kalır.
3. **Build History** (`/history`): tüm analizler, en yenisi üstte, Jenkins tarzı tablo.

**AI Analysis** neyi gösterir:

| Build | Görünüm |
|---|---|
| SUCCESS | yeşil "Build başarılı. Analiz edilecek bir hata yok." (LLM çağrısı yok) |
| **tek başarısız test** veya test dışı bir hata | **HATA SEBEBİ** (AI'ın kök nedeni) → *nerede*: hata bir testten geliyorsa IDE benzeri görünüm (feature dosyası, senaryo, başarısız step ve satırı, hata), değilse sade bir **HATA ÖZETİ** kartı (Maven / Jenkins / pipeline / altyapı) → **ÇÖZÜM ÖNERİLERİ** (≤ 3 aksiyon) |
| **birden fazla başarısız test** | **HATA SEBEBİ** = AI'ın build özeti + "10 başarısız test · 3 hata grubu · 2 grup AI ile ayrıntılı analiz edildi"; runner, logda ayrıntısı bulunandan fazla hata saydıysa bir bilgi satırı; analiz edilmemiş gruplar varsa bir uyarı. Ardından **her hata grubu için bir kart**: grup id'si, exception, test sayısı, hata imzası, grubun kendi HATA SEBEBİ / ÇÖZÜM ÖNERİLERİ (veya "AI analizi yok: <neden>"), etkilenen testlerin listesi — birini seçmek onu aynı IDE benzeri görünümde açar |

IDE benzeri görünüm logda olmayan bir dosya veya satırı asla göstermez: düz bir JUnit testi yalnızca adıyla
gösterilir. Arayüz etiketleri, spesifikasyonun öyle verdiği yerlerde İngilizcedir (Analyze Build, Build History, …);
AI içeriği ve başlıklar Türkçedir (`HATA SEBEBİ`, `ÇÖZÜM ÖNERİLERİ`; arayüzde "Kök Neden" / "Aksiyon" kelimeleri
kullanılmaz).

## Bir analiz nasıl çalışır

```
POST /api/v1/analysis/build {buildUrl}
  1. JenkinsBuildUrl.parse          job yolu ("/" ile birleşen klasörler) + build numarası — build URL'si değilse 400
  2. Jenkins consoleText            ham byte'lar → ConsoleLogDecoder (UTF-8, satır bazında cp1254 yedeği)
  3. BuildContextBuilder            durum, test sayıları, birincil hata, tüm başarısız testler (gruplanmış), log kesiti, son satırlar
  4. SUCCESS?                       sabit mesaj, prompt yok, LLM çağrısı yok
  5. ≤ 1 başarısız test             tek hata prompt'u → 1 LLM çağrısı (RootCauseSchema.ROOT_CAUSE)
     birden fazla başarısız test    grup prompt'ları → normalde 1, en fazla 3 LLM çağrısı (RootCauseSchema.FAILURE_GROUPS)
  6. ayrıştır + biçimlendir         RootCauseParser / RootCauseFormatter; grup başına durum ANALYZED / SKIPPED / FAILED
  7. kaydet                         tek `analyses` satırı (result_json) → satır id'siyle cevap
```

### 1. Logu okuma (`BuildContextBuilder`)
Çıkarım, bilinen Jenkins / Maven / BDD / stack trace kalıpları üzerinde string/regex eşleştirmesidir; mümkün olan
yerde teknolojiden bağımsızdır (hiçbir framework şart değil) ve her alan elden geldiğince doldurulur
(`null` = bulunamadı).

- **Build durumu:** Jenkins `Finished: X` (son geçtiği yer) Maven `BUILD X`'e üstün gelir. SUCCESS → kategori
  `NONE`, hiç hata ayrıştırması yapılmaz (yeşil loglarda sınıflandırılmaması gereken zararsız uyarılar bulunur).
- **Test sayıları** (`TestSummaryExtractor`): runner'ın kendi özet satırı — Surefire/Failsafe toplamları (tercih
  edilen: bir Maven modülündeki her testi sayarlar, Cucumber JUnit-runner senaryoları dahil; hiçbir şey iki kez
  sayılmasın diye sınıf bazındaki satırlar atlanır; birden fazla modül toplanır), yoksa Cucumber
  `30 Scenarios (10 failed, 20 passed)`, Gradle `30 tests completed, 10 failed`, pytest `== 3 failed, 20 passed ==`,
  Jest `Tests: 2 failed, 28 passed, 30 total`. Formatın yazdırmadığı bir sayı `null` kalır (pytest toplam yazmaz).
- **Hata kategorisi** (`ErrorClassifier`): build düzeyinde tek bir aile — SELENIUM, MAVEN, CUCUMBER, JENKINS, INFRA,
  UNKNOWN — sıralı bir anahtar kelime kural listesinden. Tek tek hataların exception tipleri bu tek alanda değil,
  kendi gruplarında tutulur.
- **Birincil hata** (tek hata alanları: senaryo, step, exception tipi, tam exception bloğu, ilk uygulama frame'i,
  başarısız UI etkileşimi): logdaki **ilk exception** — ancak o exception hiçbir başarısız teste ait değilse (geçen
  bir senaryonun logladığı bir retry, bir kurulum uyarısı) ve başarısız bir test exception yazdırmışsa, o zaman
  **ilk başarısız test**. Tek hata prompt'u ve düz API alanları bunu kullanır.
- **İlgili log kesiti** (`RelevantLogExtractor`): gürültü atılır (indirmeler, git, `[Pipeline]`, Maven yardım
  metinleri), sinyal satırları bağlamıyla tutulur, işaret satırları tek başına tutulur, tekrarlar ayıklanır,
  ≤ 120 satır. **Son 200 satır** ham olarak saklanır.

### 2. Tüm başarısız testleri bulma (`FailureExtractor`)
Başarısız test, bir testin kendi çıktısı içinde yazdırılmış bir exception bloğudur. Bir testin çıktısı şunlardan
biriyle başlar:
- bir Gherkin senaryo satırı — `Scenario:`, `Scenario Outline:`, `Example:`, Türkçe `Senaryo:` / `Örnek:`
  (Cucumber'ın veya herhangi bir BDD aracının pretty çıktısı), ya da
- bir runner'ın test bazındaki sonuç başlığı — Surefire/Failsafe `[ERROR] Example.Open Google -- Time elapsed … <<< ERROR!`
  (2.x: `Open Google(Example)  Time elapsed …`), Gradle `LoginTest > invalidPassword() FAILED`,

ve bir sonraki testte veya bir koşu özetinde biter (`Tests run:`, `30 Scenarios (…)`, `Results:`, `BUILD …`,
`Running X`). Herhangi bir testin dışındaki exception'lar (Maven, Jenkins, altyapı, koşudan sonra) test hatası
**sayılmaz**.

- **Testin hatası hangi exception'dır:** Cucumber'ın **başarısız step'in hemen altına girintili** yazdırdığı hata;
  uygulamanın senaryo sırasında sadece logladığı bir exception onun yerini almaz. Böyle bir hatası olmayan bir
  senaryo (bir hook hatası veya ele alınmış bir exception loglayan geçen bir senaryo) ancak başarısız olduğunu bir
  şey doğrularsa sayılır — Cucumber'ın `Failed scenarios:` listesi veya runner'ın raporu —, logda ikisi de yoksa hariç.
- **İki kez yazdırılma:** pretty formatter ve runner raporu aynı hatayı yazdırır (build #7 onu üç kez yazdırır).
  Bir rapor, adı senaryonun adıysa o senaryonun tekrarıdır — `Example.Open Google`, `Login.Login with user #2`
  (tekrarlanan adlar), `Open Google(Example)` —, bu olmazsa aynı hatayı taşıyorsa. Senaryo başına bir rapor; bu
  yüzden aynı adlı outline satırları yine ayrı sayılır.
- **Yeniden koşmalar (rerun):** aynı konumda yeniden yazdırılan bir senaryo veya aynı adla tekrarlanan bir rapor
  (Surefire `rerunFailingTestsCount`) bir kez sayılır — son koşu. Surefire'ın `Flakes:` altında listelediği testler
  (önce düşen, rerun'da geçen) hata değildir.
- **`Failed scenarios:` listesi** (varsa) hangi senaryoların düştüğüne karar verir; listede olup exception'ı
  yazdırılmamış bir senaryo exception alanları `null` olarak tutulur. Liste girdileri URL-decode edilir
  (`Odeme%20akisi.feature`).
- **Her hata için:** senaryo / test adı, başarısız step (metin, feature dosyası, satır — Cucumber'ın
  `at ✽.<step>(…feature:N)` sözde frame'inden veya pretty step satırından, asla başka bir testin step'i değil),
  exception tipi, mesajın ilk satırı (REST-assured'ın "1 expectation failed." satırında bir sonraki satır da
  alınır), tam exception bloğu, ilk uygulama frame'i (`File.java:line`, Gradle'ın `at app//…` frame'leri de),
  başarısız UI etkileşimi (Selenium komutu + locator) ve exception'dan hemen sonra loglanan birkaç satır
  (`logExcerpt`, ör. bir response gövdesi; bir sonraki senaryonun tag'lerinde durur).
- Exception başlıkları tam nitelikli `…Exception` / `…Error` / `…Failure` token'larının tamamıdır (JUnit 4'ün
  `ComparisonFailure`'ı dahil) — asla `GlobalExceptionHandlerTest` veya `LoginErrorSteps` gibi bir sınıf adının
  parçası değil. ANSI renk kodları ve Windows satır sonları ele alınır. Hatalar log sırasıyla tutulur.

### 3. Gruplama (`FailureGrouper`)
Aynı **hata imzasına** sahip başarısız testler bir grup oluşturur:

```
exception tipi + ": " + mesajın ilk satırı (koşuya özgü değerler maskelenmiş) + " @ " + hata konumu
org.openqa.selenium.NoSuchElementException: no such element: … #login-btn … @ stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)
```

- Yalnızca aynı hatanın iki koşusu arasında değişen değerler maskelenir: session id'leri, UUID'ler, zaman
  damgaları, portlar, nesne hash'leri, 10+ haneli sayılar. Locator'lar, durum kodları ve beklenen/gerçek değerler
  korunur.
- Konum ilk uygulama frame'idir, yoksa düşen step — dolayısıyla **aynı exception tipi başka bir yerde veya başka bir
  mesajla başka bir gruptur**. Tek başına aynı tip asla aynı neden sayılmaz.
- Gruplar `G1, G2, …` diye numaralanır; önce en büyüğü, sonra log sırası. Bir grubun ilk testi onun
  **temsilcisidir**: kanıtları LLM'e gider ve tam stack trace'i bir kez saklanır.
- Hiç exception'ı olmayan hatalar tek bir grubu paylaşır: "Logda exception / stack trace yok".

### 4. LLM çağrısı
**En fazla bir başarısız test** → **tek hata prompt'u** (Türkçe, kısa ve yönlendirici):
1. rol: bir rapor yazarı değil, bir Jenkins build hatası **karar destek sistemi** — "Neden patladı? Nerede patladı?
   Ne yapmalıyım?" sorularını on saniyede cevaplatmak; kaynak önceliği ve spekülasyon karşıtı kurallar;
2. `=== 1. YAPISAL KANIT ===` — **null olmayan** her bilgi için bir `- Etiket: değer` satırı (Build Durumu, Hata
   Kategorisi, Test Özeti, Senaryo, Başarısız Adım, Step Definition, Hata Dosyası:Satırı, Exception, Otomasyon
   Aracı, Başarısız Komut, Locator Tipi/Değeri, Kod Dili) + tam stack trace — birincil kaynak;
3. `=== 2. İLGİLİ LOG KESİTİ ===` — ikincil; `=== 3. JENKINS LOG — SON 200 SATIR ===` — yalnızca destekleyici
   (500 karakterden uzun satırlar kırpılır);
4. en sonda JSON cevap formatı: `{"rootCause", "file", "line", "method", "actions"}` (katı şema).

Build #7'nin prompt'u ~20 bin karakter (~6 bin token).

**Birden fazla başarısız test** → **grup prompt'ları** (`PromptBuilder.buildGroupPrompts`):
- build özeti (durum, kategori, test sayıları, bulunan başarısız testler / gruplar), **her** grup için bir dizin
  satırı, ardından — bu istekteki her grup için — imzası, temsilcinin bilgileri (step, step definition,
  dosya:satır, exception, UI etkileşimi), etkilenen testlerin **sayılı** listesi (≤ 10 ad + "… ve N test daha";
  sayı her zaman eksiksizdir), **tek** temsilci stack trace (≤ 40 satır) ve onun log alıntısı;
- geçen testler yalnızca sayılır, asla listelenmez; build genelinde log kesiti veya ham son satırlar yok (büyük bir
  koşuda bunlar çoğunlukla geçen senaryolardır);
- ek kurallar: sayılar ve test listeleri uygulamadan gelir, değiştirilmemelidir; aynı exception tipi aynı neden
  değildir; ortak bir neden ancak kanıt gösteriyorsa söylenir, aksi halde model kanıtlanamadığını söylemelidir;
  eksik kanıt doldurulmaz, eksik olduğu söylenir;
- cevap: `{"summary": "...", "groups": [{"id": "G1", "rootCause": "...", "actions": ["..."]}]}`
  (`RootCauseSchema.FAILURE_GROUPS`, katı) — `rootCause` ≤ 3 cümle (ne oldu, hangi koşulda, hangi kanıta göre),
  ≤ 3 önceliklendirilmiş aksiyon.

**Çağrı bütçesi** (`analysis.*`, bkz. [Yapılandırma](#yapılandırma-srcmainresourcesapplicationyml)): gruplar,
prompt `max-prompt-chars` (60 000) ve `max-groups-per-call` (6) sınırları içinde kaldıkça tek prompt'a
paketlenir; en fazla `max-llm-calls` (3) prompt oluşturulur. Normal bir build **tek** çağrı gerektirir. Büyüyebilen
her alan kırpılır (imza, bilgiler, trace satırları, log satırları), böylece log ne kadar büyük olursa olsun prompt
sınırlı kalır. Sınırlar karakter sayısıdır — token tahmini iddia edilmez.

**Grup başına durum** (`FailureGroupAnalysis`): `ANALYZED`; `SKIPPED` — çağrı sınırının üstünde, gönderilmedi (yine
de tüm testleriyle listelenir); `FAILED` — çağrı başarısız oldu, cevap okunamadı veya grup için bir şey içermiyordu,
ya da (tek hatalı build'de) prompt'taki kanıt başka bir exception'dı. Analiz edilmemiş her grubun bir `aiNote`'u
vardır. Başarısız bir çağrı yalnızca kendi gruplarını kaybettirir; analiz ancak **hiçbir** çağrı geçmezse başarısız
olur (502, hiçbir şey kaydedilmez). Metrikler cevaplanan çağrıların toplamıdır (başarısız bir çağrı kullanım
bildirmez).

**Kimlik bilgileri her prompt'ta maskelenir**, prompt uygulamadan çıkmadan önce: `password=` / `-Dtoken: x` /
`DB_PASSWORD=` / `x-api-key:` değerleri, kimlik bilgisi gibi adlandırılmış JSON alanları (kaçışlı
`\"password\":\"…\"` de), `Authorization: Basic|Bearer …` (REST-assured loglarındaki `Authorization=Basic …` de),
tek başına duran uzun bearer token'lar, URL'lerdeki `user:password@`, `curl -u user:password`, Gherkin
anahtar/değer satırları `| password | … |`. Maskelenmeyenler: boş değerler, boşluksuz `name:value`
(`LoginTest.invalidPassword:57` satır numarasını korur), `#password:focus` gibi CSS seçicileri. Kimlik bilgisi gibi
adlandırılmış bir JSON alanı, bir doğrulama mesajı tutsa bile maskelenir.

### 5. Cevabın işlenmesi ve çıktı formatı
- `RootCauseParser`, nesnenin etrafındaki bir ```` ```json ```` bloğunu veya metni tolere eder ve değerleri
  normalleştirir: yalnızca dosya adı (yol/paket/`:line` kaldırılır), basit metot adı, `line <= 0` → `null`, liste
  işaretleri temizlenir, ≤ 3 aksiyon, değer başına tek satır. `RootCauseAnalysis.withFallbackLocation` eksik
  dosya/satır/metodu parser'ın kanıtından doldurur. Ayrıştırılamayan bir cevap olduğu gibi gösterilir.
  `parseGroups` id'si veya kök nedeni olmayan grupları atar (servis onları cevapsız olarak bildirir); bilinmeyen
  id'ler yok sayılır.
- `RootCauseFormatter` gösterime hazır `aiAnalysis` metnini üretir, **en fazla 10 satır**:
  ```
  🚨 KÖK NEDEN                              🚨 BUILD ÖZETİ
  name=qqqqqqqq locator'ı bulunamadı; …      10 başarısız testin 5'i #login-btn …
  📍 KONUM                                   🧩 HATA GRUPLARI
  ExampleSteps.java:28                      - G1 (5 test): #login-btn bulunamadı …
  clickSearchBox()                          - G2 (4 test): pay-now-btn 10 sn içinde …
  ✅ AKSİYON                                 - G3 (1 test): AI analizi yok (…)
  - ExampleSteps.java:28'deki locator'ı …
  ```
- **Çıktı felsefesi:** kullanıcılar (QA Engineer, QA Lead, SDET, Developer, Team Lead) *"Neden patladı? Nerede
  patladı? Ne yapmalıyım?"* sorularını yaklaşık on saniyede cevaplayabilmelidir. Teknik açıklama bölümü yok, eğitim
  metni yok ("Selenium nedir"), kanıtın göstermediği spekülasyon yok ("iframe olabilir", "cache", "wait
  eklenmeli"); spesifik bir kök neden ("Element bulunamadı" değil, "name=qqqqqqqq locator'ı bulunamadı"), kanıttan
  türetilmiş aksiyonlar, güven seviyesi yok (ürün kararıyla kaldırıldı).
- **2026-09-27'de ölçüldü** (gpt-5-mini, `OpenAiLiveBuild7Test`): build #7 ve üç sentetik örnek log, her biri
  3–6 sn'de ve $0.0011–0.0026 maliyetle doğru dosya:satırı, spesifik bir Türkçe kök nedeni ve 2–3 aksiyonu verdi.
  Zayıf nokta: üçüncü aksiyon bazen genel kalıyor.

## API

| Endpoint | Döndürdüğü |
|---|---|
| `POST /api/v1/analysis/build` `{"buildUrl": "..."}` | analizi çalıştırır + kaydeder; aşağıdaki cevap, `generatedPrompt` **ile** |
| `GET /api/v1/analyses` | geçmiş, en yenisi önce: `id, jobName, jobPath, buildNumber, buildUrl, buildStatus, analysisStatus, errorCategory, headline, analyzedAt` (log yok, `result_json` yok) |
| `GET /api/v1/analyses/{id}` | kaydedilmiş tek bir analiz, POST cevabıyla aynı yapıda, `generatedPrompt` **olmadan** (hiç saklanmaz); yoksa 404 |

`buildUrl`'yi `domain/model/JenkinsBuildUrl` ayrıştırır: `/job/Team/job/UI-Test/125/` → job `Team/UI-Test`, build
`125`; sondaki `/console` gibi parçalar, query ve fragment yok sayılır; context path ve port korunur. Boş → 400;
Jenkins build URL'si değil (`/job/<name>/<number>/` yok, Blue Ocean, `lastFailedBuild`, `/` içeren multibranch
adları) → **hiçbir şey çalışmadan 400**. Log her zaman yapılandırılmış `JENKINS_URL`'den çekilir; bunun için
URL'deki host kullanılmaz.

Gerçek, başarısız build #7 için cevap (uzun değerler kısaltıldı):
```json
{
  "id": 1,
  "jobName": "Mini-UI-Automation",
  "buildNumber": 7,
  "buildUrl": "http://localhost:8080/job/Mini-UI-Automation/7/",
  "analyzedAt": "2026-10-04T09:42:00Z",
  "buildStatus": "FAILURE",
  "failedScenario": "Open Google",
  "featureFile": "src/test/resources/features/Example.feature",
  "featureLine": 4,
  "failedStepText": "Arama kutusuna tıkla.",
  "failedStepLine": 6,
  "exceptionType": "org.openqa.selenium.NoSuchElementException",
  "errorCategory": "SELENIUM",
  "exceptionFile": "ExampleSteps.java",
  "exceptionLine": 28,
  "failedStepDefinition": "stepdefinitions.ExampleSteps.clickSearchBox",
  "automationTool": "Selenium",
  "failedCommand": "findElement",
  "locatorType": "name",
  "locatorValue": "qqqqqqqq",
  "stackTrace": "org.openqa.selenium.NoSuchElementException: no such element ... Session ID ... at ...",
  "relevantLogSnippet": "Scenario: Open Google ... * Arama kutusuna tıkla. ... [INFO] BUILD FAILURE ... Finished: FAILURE",
  "last200Lines": "<ham log sonu>",
  "generatedPrompt": "Sen bir Jenkins build hatası karar destek sistemisin. ...",
  "aiAnalysis": "🚨 KÖK NEDEN\nname=qqqqqqqq locator'ı bulunamadı; ...\n📍 KONUM\nExampleSteps.java:28\nclickSearchBox()\n✅ AKSİYON\n- ...",
  "rootCauseAnalysis": {
    "rootCause": "name=qqqqqqqq locator'ı bulunamadı; org.openqa.selenium.NoSuchElementException atıldı.",
    "file": "ExampleSteps.java", "line": 28, "method": "clickSearchBox",
    "actions": ["ExampleSteps.java satır 28'deki findElement(using=name, value=qqqqqqqq) locator değerini düzelt."]
  },
  "testContext": { "scenario": "Open Google", "step": "Arama kutusuna tıkla.", "featureFile": "src/test/resources/features/Example.feature", "featureLine": 6 },
  "testSummary": { "total": 1, "passed": 0, "failed": 1, "skipped": 0 },
  "aiSummary": null,
  "failureGroups": [{ "id": "G1", "failureCount": 1, "aiStatus": "ANALYZED", "...": "aşağıya bakın" }],
  "provider": "OPENAI",
  "model": "gpt-5-mini-2025-08-07",
  "promptTokens": 5880,
  "completionTokens": 574,
  "totalTokens": 6454,
  "responseTimeMs": 5968,
  "estimatedCostUsd": 0.002618
}
```

**Birden fazla başarısız testi** olan bir build ek olarak `aiSummary`'yi ve grup başına bir girdiyi doldurur
(`rootCauseAnalysis: null` olur; `aiAnalysis` 🚨 BUILD ÖZETİ / 🧩 HATA GRUPLARI metnidir):
```json
"testSummary": { "total": 30, "passed": 20, "failed": 10, "skipped": 0 },
"aiSummary": "10 başarısız testin 5'i #login-btn ...; gruplar arasında ortak bir neden kanıtlanamadı.",
"failureGroups": [{
  "id": "G1",
  "signature": "org.openqa.selenium.NoSuchElementException: no such element: ... @ stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)",
  "failureCount": 5,
  "exceptionType": "org.openqa.selenium.NoSuchElementException", "message": "no such element: ...",
  "exceptionFile": "LoginSteps.java", "exceptionLine": 31,
  "stackTrace": "<temsilcinin tam exception bloğu>", "logExcerpt": null,
  "aiStatus": "ANALYZED", "rootCause": "...", "actions": ["..."], "aiNote": null,
  "failures": [{
    "testContext": { "scenario": "Hatalı giriş 6", "step": "Giriş butonuna tıkla.", "featureFile": "src/test/resources/features/Shop.feature", "featureLine": 30 },
    "exceptionType": "org.openqa.selenium.NoSuchElementException", "message": "no such element: ...",
    "exceptionFile": "LoginSteps.java", "exceptionLine": 31
  }]
}]
```

Alan notları:
- Çıkarılan her alan elden geldiğince doldurulur: `null` "bulunamadı" demektir, asla tahmin değildir.
- Düz tek hata alanları (`failedScenario` … `stackTrace`) build'in **birincil** hatasını anlatır.
- `testContext` (scenario, step, featureFile, featureLine = step'in satırı) hata bir testten geliyorsa doludur;
  Maven / Jenkins / altyapı hatalarında `null`'dır — frontend'in "IDE görünümü mü, sade hata kartı mı" için tek
  kuralı. `failureGroups[].failures`'ın her girdisi kendi `testContext`'ini taşır.
- Logda özet satırı yoksa `testSummary` `null`'dır; format bir sayıyı yazdırmıyorsa o sayı `null`'dır.
- Hiç başarısız test bulunamadığında ve çoklu hata desteğinden önce kaydedilmiş analizlerde `failureGroups` `[]`'dir.
  Her başarısız test için değil, grup başına yalnızca temsilcinin stack trace'i ve log alıntısı saklanır.
- `aiStatus`: `ANALYZED` / `SKIPPED` / `FAILED`, nedeni `aiNote`'ta (bkz. [LLM çağrısı](#4-llm-çağrısı)).
- `generatedPrompt` `@JsonInclude(NON_NULL)` taşır: bir LLM çağrısından sonraki POST cevabında bulunur (birden fazla
  çağrı olduysa tüm prompt'lar), GET cevaplarında ve SUCCESS build'lerde yoktur.
- `model`, OpenAI'ın bildirdiği değerdir (tarihli bir snapshot). `promptTokens` / `completionTokens` /
  `totalTokens`, Responses API'nin `input_tokens` / `output_tokens` / `total_tokens` değerleridir — çıktı, GPT-5'in
  gizli reasoning token'larını içerir — ve cevaplanan çağrılar üzerinden toplanır. `estimatedCostUsd` = token'lar ×
  yapılandırılmış liste fiyatları (6 ondalık; önbellekli girdi indirimleri yok sayılır, yani üst tahmin; başarısız
  çağrılar dahil değil). `responseTimeMs` yalnızca LLM süresidir.
- **SUCCESS build:** prompt yok, LLM çağrısı yok — `errorCategory` = `NONE`; hata alanları, `testContext`,
  `rootCauseAnalysis` ve kullanım alanları `null`; `failureGroups` `[]`; `aiAnalysis` = "Build başarıyla
  tamamlandı. Analiz gerektiren bir hata tespit edilmedi."; `testSummary` yine de okunur.
- **Hatalar:** 400 doğrulama / build URL'si değil; Jenkins 2xx dışı → aynı durum kodu (401/403 = hatalı veya eksik
  kimlik bilgisi, 404 = böyle bir job/build yok); Jenkins'e ulaşılamıyor → 502; OpenAI'a ulaşılamıyor / zaman
  aşımı / 2xx dışı (401 yanlış anahtar, 429 hız sınırı veya kota) / boş veya reddedilmiş cevap → 502
  `{"error": "AI analysis failed: ..."}`. Başarısız analizler kaydedilmez.

## Veritabanı

Tek tablo, **`analyses`** — her satır analiz edilmiş bir Jenkins build'i. Şema Flyway ile
(`src/main/resources/db/migration`); Hibernate yalnızca doğrular (`ddl-auto: validate`).

- `V1__init_schema.sql` users / projects / project_members / pipelines / analyses tablolarını oluşturdu;
  `V2__drop_projects_and_pipelines.sql` `analyses` dışındaki her şeyi kaldırdı (ürün kararı: bir analizi tek başına
  build URL'si tanımlar). **Uygulanmış bir migration'ı asla düzenlemeyin; yenisini ekleyin (`V3__…`).**
- Kolonlar: Jenkins (`build_url, job_name, job_path, build_number, build_status`), geçmiş listesi
  (`analysis_status, error_category, exception_type, headline`), sonuç (`error_reason, result_json, relevant_logs,
  last_200_lines`), LLM (`model, total_tokens, estimated_cost_usd`), `created_at, analyzed_at`.
- `result_json` (TEXT, Jackson), prompt, loglar ve başlık alanları (kendi kolonları var) hariç analiz cevabıdır —
  `testSummary`, `aiSummary` ve her başarısız testiyle birlikte her hata grubu dahil. **Prompt ve konsol logunun
  tamamı asla saklanmaz** (log Jenkins'te kalır).
- `analysis_status`: `COMPLETED` · `SKIPPED` (SUCCESS build, LLM çağrısı yok) · `UNSTRUCTURED` (modelden
  kullanılabilir cevap yok) · `PARTIAL` (bazı hata gruplarının AI cevabı yok — çağrı sınırı aşıldı, bir çağrı
  başarısız oldu veya kanıt gönderilmedi).
- `headline` (geçmiş etiketi): basit exception adı, yoksa hata kategorisi; SUCCESS için "Build başarılı"; birden
  fazla başarısız testte "10 başarısız test, 3 hata grubu". `exception_type` birincil hatanın tipidir.
- `error_reason`: kök neden, build özeti veya ham cevap.
- Her analiz yeni bir satırdır — aynı build'i iki kez analiz etmek iki satır verir. Kimlik doğrulama yok:
  uygulamaya erişebilen herkes tüm geçmişi görür.
- Veriye bakmak için: pgAdmin → `build_analyzer` → `analyses`, ya da
  `psql -h localhost -U build_analyzer_user -d build_analyzer` (psql `C:\Program Files\PostgreSQL\18\bin` içinde).

## Frontend

`frontend/` — React 19, TypeScript, Vite, MUI 9, React Router 8; backend çağrıları tarayıcının `fetch`'i ile.
Bilerek küçük (yalın V1): Redux/Zustand/TanStack Query yok — state, sayfada veya bileşende
`useState`/`useEffect` ile tutulur; düz klasörler, feature/domain/mapper katmanı yok. Başta yazılan uzun frontend
tasarım dokümanı (repo dışında tutuluyor) ürün vizyonudur, uygulanan kapsam değil.

```
src/
  api/analysisApi.ts        tüm backend çağrıları + toAnalysis() (backend cevabı → ekranların gösterdiği)
  types/analysis.ts         AnalysisResponse (backend), Analysis (ekranlar), AnalysisSummary (geçmiş satırı),
                            TestContext, TestSummary, FailureGroup, TestFailure
  pages/                    DashboardPage (URL girişi + geçmiş), AnalysisPage (/analyses/:id), HistoryPage (/history)
  components/               AnalyzeForm, HistoryList, AiAnalysis (tekli görünüm + hata grubu kartları),
                            TestContextView (IDE benzeri), LogViewer, StatusChip, Layout, Sidebar
  utils/jenkinsUrl.ts       anında form geri bildirimi için URL ayrıştırıcı (backend yeniden ayrıştırır; esas olan JenkinsBuildUrl)
  utils/format.ts           tarih, test özeti ve tip adı biçimlendirme
  theme.ts                  MUI teması + monospace font
```

```
npm run dev      # http://localhost:5173, /api istekleri :8090'a yönlendirilir
npm test         # Vitest
npm run build    # tip kontrolü + frontend/dist içine production paketi
```

- Akış: `DashboardPage` (URL → `AnalyzeForm` → `POST /api/v1/analysis/build`) → `AnalysisPage`
  (`GET /api/v1/analyses/{id}`, AI Analysis / Relevant Logs sekmeleri) · `HistoryList` (`GET /api/v1/analyses`).
- **Gösterim kuralları:** `ai === null` → başarı mesajı. `failureGroups`'ta birden fazla başarısız test → build
  özeti + grup kartları. Aksi halde `testContext` karar verir: null değil → IDE benzeri görünüm, null → HATA ÖZETİ
  kartı. Frontend hiçbir test framework'ünü bilmez; `testContext`'in her alanı opsiyoneldir.
- Bir grup kartındaki test listesi gerçek bir listedir (`ul > li > button`); seçilen test `TestContextView`'da
  açılır. Kartların key'i analiz id'si + grup id'sidir, böylece başka bir analiz kendi ilk testi seçili olarak
  başlar.
- `index.html` bilerek `lang="en"` kullanır: `tr` ile MUI'nin büyük harfe çevirmesi "Build"i "BUİLD" yapar; Türkçe
  başlıklar kaynakta büyük harfle yazılır ("HATA SEBEBİ").

## Mimari

Hexagonal (Ports & Adapters): **infrastructure → application → domain**; domain framework'le ilgili hiçbir şey
import etmez, application asla `infrastructure.*` import etmez (`ArchitectureBoundaryTest` ile zorlanır).

```
POST /api/v1/analysis/build
  → BuildAnalysisController (api)
    → JenkinsBuildUrl.parse(buildUrl)                                              (domain; geçersizse 400)
    → AnalyzeBuildUseCase → AnalyzeBuildService (application)
        → BuildSourcePort → JenkinsBuildSourceAdapter → JenkinsHttpClient → GET /job/{a}/job/{b}/{n}/consoleText
        → BuildContextBuilder → BuildAnalysisContext (domain)
              durum · TestSummaryExtractor · ExceptionBlockExtractor · ScenarioExtractor · StepExtractor
              · InteractionExtractor[] · ErrorClassifier · RelevantLogExtractor · son satırlar
              · FailureExtractor → FailureGrouper (tüm başarısız testler, gruplanmış)
        → SUCCESS → sabit mesaj
        → ≤ 1 başarısız test → PromptBuilder.build → LlmProvider [ROOT_CAUSE] → RootCauseParser → RootCauseFormatter
        → birden fazla → PromptBuilder.buildGroupPrompts → LlmProvider [FAILURE_GROUPS] × ≤ 3 → parseGroups → formatGroups
        → BuildAnalysisResult (context, prompt(s), aiAnalysis, rootCauseAnalysis, llmMetrics, aiSummary, groupAnalyses)
    → BuildAnalysisResponseMapper.toEntity → AnalysisRepository.save → PostgreSQL
    → BuildAnalysisResponseMapper.toResponse → AnalyzeBuildResponse (DTO, kaydedilen id ile)

GET /api/v1/analyses       → AnalysisRepository.findAllByOrderByAnalyzedAtDesc()  (Summary projection)
GET /api/v1/analyses/{id}  → AnalysisRepository.findById → mapper.toResponse(entity)  (yoksa 404)
```

Analiz çekirdeği (`application`, `domain`) veritabanını bilmez; kaydetme, `AnalyzeBuildService` döndükten sonra
controller'da yapılır.

**LLM sağlayıcı bağımsızlığı:**
```
application (yalnızca port'u bilir)        infrastructure (sağlayıcı başına bir adapter)
┌──────────────────────────────┐           ┌──────────────────────────────────────────┐
│ AnalyzeBuildService          │           │ llm/openai/OpenAiConfiguration           │
│   └─ LlmProvider (port)  ◄───┼───────────┤   @ConditionalOnProperty llm.provider=   │
│        complete(LlmRequest)  │           │     OPENAI (varsayılan)                  │
│        → LlmCompletion       │           │   → OpenAiProvider + OpenAiProperties    │
└──────────────────────────────┘           │ llm/TokenPricing (ortak maliyet tahmini) │
                                           └──────────────────────────────────────────┘
```
- `llm.provider` (`LLM_PROVIDER`, varsayılan `OPENAI`) adapter'ı seçer; tam olarak bir `LlmProvider` bean'i vardır;
  aktif olmayan bir sağlayıcının ayarları ne bağlanır ne de zorunludur.
- **Sağlayıcı eklemek** (ör. Anthropic veya yeniden Ollama) = tek bir paket, `infrastructure/llm/<provider>/`:
  1. `<Provider>Provider implements LlmProvider` — `LlmCompletion` döndürür, her taşıma hatasını
     `LlmAnalysisException` içine sarar (`RestClientResponseException`'ın dışarı kaçmasına asla izin vermeyin: API
     katmanı bu tipi Jenkins hatalarına eşler) ve `LlmRequest.responseSchema`'yı kendi yöntemiyle uygular;
  2. `<Provider>Properties` (`@ConfigurationProperties("<provider>")`, `@Validated`, anahtar yalnızca ortamdan);
  3. `<Provider>Configuration` — `@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = "<NAME>")`
     + `@EnableConfigurationProperties`, bean'i tanımlar;
  4. `application.yml` içinde bir `<provider>:` bloğu ve in-process HTTP stub'lı bir adapter testi.
  application, domain, api veya `PromptBuilder` içinde hiçbir şey değişmez.

## Paket / sınıf haritası
Temel paket: `com.company.buildanalyzer`

### domain (saf Java — Spring yok, framework import'u yok)
- `model/BuildAnalysisContext` — buildStatus, `FailedScenario failedScenario`, `FailedStep failedStep`,
  exceptionType, `ErrorCategory errorCategory`, stackTrace (tam blok), `FailureLocation failureLocation`,
  `FailedInteraction failedInteraction` (birincil hata), relevantLogSnippet, last200Lines,
  `TestSummary testSummary`, `List<FailureGroup> failureGroups`. `isSuccessful()` / static
  `isSuccessStatus(String)` "SUCCESS, analiz gerekmez" için tek doğru kaynaktır; `failureCount()` /
  `hasMultipleFailures()` akışı seçer.
- `model/TestSummary` — total, passed, failed, skipped (`Integer`, `null` = yazdırılmamış).
- `model/TestFailure` — tek bir başarısız test: `FailedScenario scenario` (düz bir test: runner'ın test adı,
  dosya/satır yok), `FailedStep step`, exceptionType, message, stackTrace, `FailureLocation`, `FailedInteraction`,
  logExcerpt. `scenario` dışındaki her alan `null` olabilir.
- `model/FailureGroup` — id (`G1`…), signature, failures (ilki = temsilci).
- `model/FailureGroupAnalysis` — groupId, status (`ANALYZED` / `SKIPPED` / `FAILED`), rootCause, actions (≤ 3), note.
- `model/FailedScenario` — name, featureFile, featureLine (`Open Google`, `src/test/resources/features/Example.feature`, `4`).
- `model/FailedStep` — text, featureFile, featureLine (`Arama kutusuna tıkla.`, `Example.feature`, `6`).
- `model/FailureLocation` — stepDefinition, file, line (`stepdefinitions.ExampleSteps.clickSearchBox`, `ExampleSteps.java`, `28`).
- `model/FailedInteraction` — araçtan bağımsız "bir hedefe karşı komut": tool, command, locatorType, locatorValue
  (`Selenium`, `findElement`, `name`, `qqqqqqqq`; Playwright'ta `toBeVisible` + `getByRole` … olurdu).
- `model/LlmMetrics` — provider, model, prompt/completion/total token'lar, responseTimeMs, estimatedCostUsd.
- `model/BuildAnalysisResult` — context, generatedPrompt, aiAnalysis, `RootCauseAnalysis rootCauseAnalysis`,
  `LlmMetrics llmMetrics`, aiSummary, `List<FailureGroupAnalysis> groupAnalyses`. Use case'in döndürdüğü şey.
- `model/RootCauseAnalysis` — rootCause, file, line, method, actions (asla null değil, değiştirilemez, en fazla 3);
  `withFallbackLocation(FailureLocation)`.
- `model/JenkinsBuildUrl` — `parse(url)` → jobPath, buildNumber, normalleştirilmiş url; aksi halde
  `IllegalArgumentException`.
- `model/ErrorCategory` — SELENIUM, MAVEN, CUCUMBER, JENKINS, INFRA, UNKNOWN, NONE (başarılı build).

### application (use case'ler, ayrıştırma, prompt'lar — Spring stereotype'ları kullanabilir; yalnızca domain'e ve port'lara bağımlı)
**Use case ve port'lar**
- `usecase/AnalyzeBuildUseCase` — inbound port: `BuildAnalysisResult analyze(String jobName, int buildNumber)`.
- `usecase/AnalyzeBuildService` — akışın sahibi: log → context → SUCCESS kısa devresi → tek hata veya grup akışı
  (çağrılar, grup başına durumlar, toplanmış metrikler) → sonuç. HTTP yok, sağlayıcı bilgisi yok.
- `port/out/BuildSourcePort` — `String fetchConsoleLog(String jobName, int buildNumber)`.
- `port/out/LlmProvider` — `LlmCompletion complete(LlmRequest)`; `LlmRequest` = prompt + opsiyonel
  `LlmResponseSchema` (ad + düz map olarak JSON şeması); `LlmCompletion` = metin, sağlayıcı, model, token'lar,
  maliyet; `LlmAnalysisException` — bir adapter'ın fırlattığı tek hata tipi.

**Context çıkarımı (`context/`)** — statik extractor'lar package-private
- `BuildContextBuilder` — aşağıdaki her şeyi yönetir; birincil hatayı seçer; son 200 satırı tutar.
- `ExceptionBlockExtractor` — `isHeader` / `extractAt` / `extract` (ilk blok). Başlık, tam nitelikli
  `…Exception|Error|Failure` token'ının tamamıdır (ANSI kodları temizlenmiş). Bloğun **tamamını** kırpmadan tutar:
  her mesaj satırı (kontrolden çıkmaya karşı en fazla 30) ve her frame (`at`, `Caused by:`, `Suppressed:`,
  `... n more`); boş bir satırda, yeni bir log kaydında (`[INFO]`, `[ERROR]`, `[Pipeline]`, …) veya frame'lerden
  sonraki ilk frame olmayan satırda biter. İlk uygulama frame'i (paketi `FRAMEWORK_PACKAGES` içinde olmayan;
  `at [loader/][module/]pkg.Class.method(File.java:N)`) → `FailureLocation`; yedek: başlığın üstündeki Cucumber
  step yorumu `# pkg.Class.method()`.
- `ScenarioExtractor` — senaryo satırları (`Scenario`, `Scenario Outline/Template`, `Example`, `Senaryo`,
  `Senaryo taslağı`, `Örnek`; temiz ad, boşluklu / `classpath:` / Windows yollu feature dosyası, ANSI temizlenmiş;
  `cleanName`, `Order #12` gibi bir addaki `#`'i korur) ve `Failed scenarios:` listeleri (URL-decode edilmiş).
  Birincil hata için: listedeki ilk girdi, yoksa exception'dan önceki son senaryo, yoksa ilki.
- `StepExtractor` — önce step sözde frame'i `at ✽.<step>(…/<file>.feature:<line>)` (`✽` çoğu zaman `?` olarak
  gelir); yoksa exception'ın üstündeki en yakın pretty step satırı (İngilizce + Türkçe anahtar kelimeler); asla bir
  senaryo başlığını veya — başarısız bir test için — o testin çıktısının başlangıcını aşmaz.
- `TestSummaryExtractor`, `FailureExtractor`, `FailureGrouper` — bkz. [Bir analiz nasıl çalışır](#bir-analiz-nasıl-çalışır).
- `InteractionExtractor` (strateji arayüzü) + `SeleniumInteractionExtractor` — `Command: [<session>, findElement
  {using=name, value=…}]` → komut + locator, yoksa mesajdaki `{"method":…,"selector":…}`. Yeni bir araç eklemek =
  yeni bir implementasyon eklemek.
- `RelevantLogExtractor` — veriye dayalı NOISE / SIGNALS / MARKERS kuralları; uyarılar tek başına asla tetiklemez;
  `[LEVEL]` önekleri yok sayılarak tekrar ayıklama; satırlar 300 karakterde kırpılır; ≤ 120 satır (1/3 baş +
  2/3 son); sinyal yoksa → gürültü olmayan son 40 satır. Selenium, Cypress, Playwright, pytest, NUnit ve Karate
  örnekleriyle test edildi.
- `classifier/ErrorClassifier` — sıralı `Rule{ErrorCategory, keyword Pattern}` listesi, ilk eşleşen kazanır.

**Prompt'lar (`prompt/`)**
- `PromptBuilder` — `build(context)` (tek hata) ve `buildGroupPrompts(context)` (birden fazla); yalnızca metin,
  Türkçe, sağlayıcıdan bağımsız; kimlik bilgilerini maskeler.
- `AnalysisLimits` (`analysis.*`) — `maxPromptChars`, `maxGroupsPerCall`, `maxLlmCalls`.

**Cevap işleme (`analysis/`)**
- `RootCauseSchema` — `ROOT_CAUSE` ve `FAILURE_GROUPS` (katı: her özellik zorunlu, `additionalProperties:
  false`; `maxItems` yok — her sağlayıcı desteklemiyor, sınırları parser uygular).
- `RootCauseParser` — `parse` / `parseGroups`; `RootCauseFormatter` — `format` / `formatGroups`.

### api (inbound adapter — Spring web)
- `controller/BuildAnalysisController` — üç endpoint.
- `dto/request/AnalyzeBuildRequest` — `{ buildUrl }` + doğrulama.
- `dto/response/AnalyzeBuildResponse` — POST'un ve GET `/analyses/{id}`'nin kullandığı düz record; iç içe
  `RootCauseAnalysisResponse`, `TestContextResponse`, `TestSummaryResponse`, `FailureGroupResponse` (başarısız test
  başına `TestFailureResponse` ile). Araçtan ve sağlayıcıdan bağımsız adlar; `failureGroups` asla `null` değildir.
- `mapper/BuildAnalysisResponseMapper` — domain → DTO (düzleştirilmiş), `testContext` kuralı, AI cevaplarıyla
  birlikte hata grupları, `toEntity` (satır: headline, analiz durumu, `result_json`), `toResponse(Analysis)`
  (`result_json` + kolonlardan).
- `error/GlobalExceptionHandler` — doğrulama → 400, Jenkins `RestClientResponseException` → kendi durum kodu,
  `ResourceAccessException` → 502, `LlmAnalysisException` → 502, `ResponseStatusException` → kendi durum kodu.

### infrastructure (outbound adapter'lar — tüm Jenkins/OpenAI/HTTP/veritabanı detayı burada)
- `persistence/Analysis` (JPA entity) ve `persistence/AnalysisRepository` (Spring Data; `Summary` = geçmiş listesi
  projection'ı, logları veya `result_json`'u asla okumaz).
- `jenkins/JenkinsBuildSourceAdapter` → `JenkinsHttpClient` (base URL, yalnızca kullanıcı adı ayarlıysa Basic
  auth, job'ın tam adından `/job/<a>/job/<b>/<n>/consoleText`, gövde **ham byte** olarak okunur) →
  `ConsoleLogDecoder` (katı UTF-8; olmazsa satır satır windows-1254 yedeği — Türkçe Windows'taki test JVM'i cp1254
  yazar); `JenkinsProperties` (`jenkins.*`).
- `llm/TokenPricing` — 1M token başına USD → `estimateUsd`; herhangi bir değer bilinmiyorsa `null`.
- `llm/openai/OpenAiConfiguration`, `OpenAiProvider` (yalnızca Responses API; connect + read timeout'lu JDK
  `HttpClient` üzerinde `RestClient`; cevap = `message` öğelerinin `output_text` parçaları; usage → token'lar;
  HTTP hataları, zaman aşımları, okunamayan cevaplar, `status=failed`, retler, boş cevaplar ve "tüm token'lar
  reasoning'e harcandı" → `LlmAnalysisException`; metni olan ama yarım kalmış bir cevap uyarıyla döndürülür),
  `OpenAiResponsesRequest` (`{model, input, max_output_tokens, reasoning.effort?, text.verbosity?, text.format?,
  store:false}`; bir şema `text.format = {type:"json_schema", name, schema, strict:true}` olur; **`temperature`
  yok** — GPT-5 varsayılan dışındaki değerleri reddeder; `store:false`, loglar OpenAI'da saklanmasın diye),
  `OpenAiResponsesResponse`, `OpenAiProperties` (`openai.*`, Java'da varsayılan değer yok).

## Yapılandırma (`src/main/resources/application.yml`)
Her değer, gösterilen ortam değişkeniyle ezilebilir. **Dosyada hiçbir gizli bilgi saklanmaz.**

| Ayar | Ortam değişkeni | Varsayılan | Not |
|---|---|---|---|
| `server.port` | — | `8090` | 8080 yerel Jenkins'e ait |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/build_analyzer` | |
| `spring.datasource.username` | `DB_USERNAME` | `build_analyzer_user` | |
| `spring.datasource.password` | `DB_PASSWORD` | boş | yalnızca ortamdan; başlamak için zorunlu |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | şema yalnızca Flyway migration'larıyla |
| `spring.jpa.open-in-view` | — | `false` | |
| `jenkins.url` | `JENKINS_URL` | `http://localhost:8080` | |
| `jenkins.username` | `JENKINS_USERNAME` | boş | boş = anonim (bu Jenkins o zaman 403 döner) |
| `jenkins.api-token` | `JENKINS_API_TOKEN` | boş | |
| `jenkins.log-charset` | `JENKINS_LOG_CHARSET` | `UTF-8` | |
| `jenkins.log-fallback-charset` | `JENKINS_LOG_FALLBACK_CHARSET` | `windows-1254` | geçerli UTF-8 olmayan satırlar için |
| `analysis.max-prompt-chars` | `ANALYSIS_MAX_PROMPT_CHARS` | `60000` | birden fazla başarısız test: tek bir grup prompt'unun hedef boyutu (karakter) |
| `analysis.max-groups-per-call` | `ANALYSIS_MAX_GROUPS_PER_CALL` | `6` | LLM çağrısı başına hata grubu (cevap uzunluğunu sınırlar) |
| `analysis.max-llm-calls` | `ANALYSIS_MAX_LLM_CALLS` | `3` | build başına en fazla LLM çağrısı; fazlası analiz edilmemiş olarak listelenir |
| `llm.provider` | `LLM_PROVIDER` | `OPENAI` | yalnızca `OPENAI` uygulanmış |
| `openai.api-key` | `OPENAI_API_KEY` | — (**zorunlu**) | yalnızca ortamdan; yoksa uygulama başlamaz |
| `openai.url` | `OPENAI_URL` | `https://api.openai.com/v1` | ör. bir şirket proxy'si |
| `openai.model` | `OPENAI_MODEL` | `gpt-5-mini` | |
| `openai.timeout-seconds` | `OPENAI_TIMEOUT_SECONDS` | `120` | cevabın tamamı için read timeout |
| `openai.connect-timeout-seconds` | `OPENAI_CONNECT_TIMEOUT_SECONDS` | `10` | |
| `openai.max-output-tokens` | `OPENAI_MAX_OUTPUT_TOKENS` | `4000` | gizli reasoning token'larını da içerir |
| `openai.reasoning-effort` | `OPENAI_REASONING_EFFORT` | `low` | minimal / low / medium / high; boş = gönderilmez |
| `openai.verbosity` | `OPENAI_VERBOSITY` | `low` | low / medium / high; boş = gönderilmez |
| `openai.pricing.input-usd-per-million` | `OPENAI_INPUT_USD_PER_MILLION` | `0.25` | gpt-5-mini liste fiyatı |
| `openai.pricing.output-usd-per-million` | `OPENAI_OUTPUT_USD_PER_MILLION` | `2.00` | gpt-5-mini liste fiyatı |

OpenAI fiyatlarını değiştirirse veya başka bir model ayarlanırsa iki fiyat değerini güncelleyin.

## Testler

**Backend:** `mvn test` — **226 test** (220 birim testi + 4 PostgreSQL testi + 2 isteğe bağlı canlı OpenAI testi).
Harici servis veya API anahtarı gerekmez: OpenAI, in-process bir HTTP sunucusu veya mock ile taklit edilir, Jenkins
mock'lanır, controller testi repository'yi mock'lar ve gerçekçi konsol logları fixture'lardan
(`src/test/resources/logs/`) veya `SyntheticConsoleLog` üretecinden gelir (gerçek bir koşudaki gibi Surefire'ın
tekrarlanan raporlarıyla, istenen sayıda geçen/düşen Cucumber senaryosu).
- `AnalysisRepositoryTest` (4 test), `DB_PASSWORD` ayarlıysa **gerçek yerel PostgreSQL**'e karşı koşar; her test
  geri alınır (rollback), veritabanında hiçbir şey kalmaz.
- `OpenAiLiveBuild7Test` (isteğe bağlı, gerçek API, ~$0.01, logları OpenAI'a gönderir) build #7'yi, üç örnek logu
  ve sentetik 10 hatalı bir build'i koşar: `mvn test -Dtest=OpenAiLiveBuild7Test -Dopenai.live=true`
  (`OPENAI_API_KEY` gerekir; cevaplar `target/live-analyses.txt`'ye yazılır).

`DB_PASSWORD` ayarlıyken: `Tests run: 226, Failures: 0, Errors: 0, Skipped: 2` (yalnızca canlı testler atlanır).
Geliştirici makinesinde `DB_PASSWORD` bir **Windows kullanıcı ortam değişkenidir**, bu yüzden yeni açılan her
terminaldeki `mvn test` onu görür (IntelliJ'in run configuration'ı ortam değişkenlerini testlere veya terminallere
aktarmaz).

Çoklu hata davranışının test edildiği yerler:

| Durum | Testler |
|---|---|
| tek başarısız test tekli akışta kalır | `AnalyzeBuildServiceTest`, `Build7OpenAiEndToEndTest`, `MultiFailureContextTest.build7HasExactlyOneFailure` |
| 30 senaryo / 10 hata → 10'u bulunur, sayılar, tek satır, tek LLM çağrısı | `MultiFailureContextTest`, `MultiFailureAnalysisTest`, `BuildAnalysisControllerTest` |
| aynı hata 10× → 10 testlik tek grup | `MultiFailureContextTest`, `MultiFailureAnalysisTest`, `FailureGrouperTest` |
| NoSuchElement / Timeout / AssertionError ayrı gruplar | `MultiFailureContextTest`, `FailureGrouperTest` |
| 1500 senaryo, az hata → tek çağrı, prompt'ta geçen test yok | `MultiFailureContextTest`, `MultiFailureAnalysisTest` |
| çok grup → sınırlı çağrı, hiçbir şey kaybolmaz, kısmi hata, okunamayan cevaplar | `MultiFailureAnalysisTest`, `GroupPromptTest` |
| eksik stack trace / konum → hiçbir şey uydurulmaz | `MultiFailureContextTest`, `BuildAnalysisResponseMapperTest`, frontend `AiAnalysis.test` |
| Maven / Jenkins / altyapı → hata grubu yok, standart kart | `MultiFailureContextTest`, frontend `AiAnalysis.test` |
| SUCCESS → LLM çağrısı yok, sayılar okunur | `AnalyzeBuildServiceTest`, `MultiFailureContextTest` |
| kalıcılık: tek satır, GET ile okunabilir, eski satırlar okunabilir | `AnalysisRepositoryTest` (gerçek PostgreSQL), `BuildAnalysisResponseMapperTest`, `BuildAnalysisControllerTest` |
| iç içe Jenkins URL'si `/job/Team/job/UI-Test/125/` | `JenkinsBuildUrlTest`, `JenkinsHttpClientTest`, frontend `jenkinsUrl.test` |
| gerçek dünya log biçimleri (runner adları, loglanmış exception'lar, rerun/flaky, Gradle, renkler, encode edilmiş yollar, REST-assured, devasa satırlar) | `FailureExtractorTest`, `TestSummaryExtractorTest` |
| kimlik bilgisi maskeleme, prompt boyut sınırları | `PromptBuilderTest`, `GroupPromptTest` |

**Frontend:** `cd frontend && npm test` — **32 Vitest testi** (URL ayrıştırıcı, cevap eşleme, `AnalyzeForm`,
`AiAnalysis` tekli ve çoklu hata gösterimi); `npm run build` tip kontrolü de yapar.

Sınıfları sildikten veya yeniden adlandırdıktan sonra `target/` içindeki eski `.class` dosyaları kullanılmasın diye
`mvn clean test` çalıştırın.

## Mimari kurallar (korunmalı)
- **Bağımlılık yönü:** infrastructure → application → domain; domain framework'süz; application asla
  `infrastructure.*` import etmez (`ArchitectureBoundaryTest`).
- **Sağlayıcı bağımsızlığı:** application yalnızca `LlmProvider` / `LlmCompletion`'ı bilir; sağlayıcı adları,
  wire formatları, anahtarlar ve fiyatlar yalnızca `infrastructure/llm/<provider>` + yapılandırmada yaşar.
- Controller'lar DTO döndürür, asla domain modeli değil (istisna: geçmiş listesi, zaten tam olarak liste satırı olan
  repository'nin `Summary` projection'ını döndürür).
- **Analiz çekirdeği veritabanını bilmez:** kaydetme ve okuma, controller'da `AnalysisRepository` üzerinden yapılır.
- **Bir build = bir analiz = bir satır;** asla test başına LLM çağrısı yok; tespit edilen hatalar LLM'e
  gönderilmedi diye asla atılmaz.
- **Şema değişiklikleri yalnızca yeni bir Flyway migration'ı ile** (`V3__…`); uygulanmış olan asla düzenlenmez.
- if-else zincirleri yerine kural listelerini / veriye dayalı yapıları tercih edin (ErrorClassifier,
  RelevantLogExtractor, TestSummaryExtractor, FRAMEWORK_PACKAGES, kimlik bilgisi ve koşuya özgü değer maskeleri).
- Ayrıştırmayı **araçtan bağımsız** tutun; araca özgü bilgi strateji implementasyonlarına gider
  (`InteractionExtractor`).
- Çıkarılan alanlar bulunamadığında `null`'dır — asla tahmin edilmez. Prompt'lar yalnızca null olmayan bilgileri
  listeler.
- Gizli bilgiler ortamdan gelir, asla commit edilmez; loglardaki kimlik bilgileri asla bir prompt'a ulaşmaz.

## Konvansiyonlar ve dikkat edilecekler
- **Makinenin varsayılanı JDK 23**, proje Java 21'i hedefler. JDK 23 annotation processor'ları artık kendiliğinden
  çalıştırmadığı için `pom.xml` Lombok'u `annotationProcessorPaths` içinde açıkça listeler — kaldırmayın.
- **Eski sınıflar:** `target/` kendiliğinden temizlenmez; sınıf sildikten sonra `mvn clean` çalıştırın.
- **`mvn spring-boot:run` sonrası kalan süreç:** basit bir kill'den sonra fork edilmiş JVM 8090'ı tutmaya devam
  eder:
  ```powershell
  Get-NetTCPConnection -LocalPort 8090 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
  ```
- **Endpoint'ten 401/403** = eksik/yanlış Jenkins kimlik bilgisi, kod hatası değil. **502 "OpenAI returned HTTP
  401"** = yanlış/iptal edilmiş `OPENAI_API_KEY`; **429** = hız sınırı veya kota yok.
- **GPT-5'e özgü:** özel `temperature` yok; `max_output_tokens` reasoning token'larını da kapsar, bu yüzden çok
  küçük bir değer boş cevaba yol açabilir (açık bir 502 olarak bildirilir).
- **Testler ve gerçek ortam:** yapılandırma bağlayan testler ya işletim sistemi ortamının yerine kendi ortamlarını
  koyar (`EnvironmentConfigBindingTest`) ya da değerleri açıkça ayarlar; aksi halde geliştirici makinesindeki bir
  `OPENAI_API_KEY` testlere sızardı.
- **Manuel test job'ı:** `Mini-UI-Automation` — #1–#5 build'leri SUCCESS; **#7 gerçek bir Selenium hatası**
  (locator `name=qqqqqqqq`, `ExampleSteps.java:28`); #9 Selenium `TimeoutException` ile düşer. Build #7'nin tam
  konsol logu `src/test/resources/logs/build-7-selenium-failure.log` fixture'ıdır.
- **PostgreSQL 18:** Flyway "PostgreSQL 18.6 is newer than this version of Flyway" diye loglar — yalnızca bir uyarı.
- **Analiz id'lerinde boşluklar var:** DB testleri satırlarını geri alır, ama PostgreSQL kullanılmış id'leri geri
  vermez.
- **Windows kabuk tuzağı (Git Bash):** Git Bash'teki `/tmp`, Windows `node`'unun gördüğü klasör değildir;
  aralarında paylaşılan dosyalar için Windows yolu kullanın.
- ⚠️ **Güvenlik:** daha önceki bir Jenkins API token'ı `application.yml` içinde commit edilmişti (ilk commit).
  Dosyadan kaldırıldı ama git geçmişinde hâlâ duruyor — Jenkins'te iptal edilmesi gerekiyor.

## Durum ve yol haritası
**Tamamlananlar**
- Yalnızca ortamdan gelen kimlik bilgileriyle ve cp1254'ü dikkate alan log çözümlemeyle Jenkins'ten çekme; iç içe
  klasördeki job'lar.
- Context çıkarımı: SUCCESS kısa devresi, test sayıları, birincil hata (senaryo, step, tam exception bloğu, kod
  konumu, UI komutu/locator), hata kategorisi, ilgili log kesiti, son 200 satır.
- **Her başarısız test** çıkarılır (pretty çıktı, Surefire/Failsafe, Gradle; rerun'lar, flaky testler, loglanmış
  exception'lar ve runner adlandırma varyantları ele alınır), hata imzasına göre gruplanır, sınırlı sayıda LLM
  çağrısıyla (normalde tek) analiz edilir; grup başına cevap ve durum, tek satır olarak saklanır, IDE benzeri test
  görünümlü grup kartları olarak gösterilir.
- Sağlayıcıdan bağımsız LLM port'u; OpenAI GPT-5 mini adapter'ı; kanıt önceliği, spekülasyon karşıtı kurallar ve
  kimlik bilgisi maskelemeli kısa Türkçe karar destek prompt'ları; katı JSON cevaplar; ≤ 10 satırlık metin çıktısı.
- Kullanım istatistikleri: sağlayıcı, model, token'lar, cevap süresi, tahmini maliyet.
- Liste ve detay endpoint'leriyle PostgreSQL geçmişi (`analyses`, Flyway V1 + V2); React frontend.
- 2026-10-04'te gerçek Jenkins, OpenAI ve PostgreSQL 18'e karşı uçtan uca doğrulandı (build #7; ~6 sn,
  ~$0.0024). Çoklu hata akışı birim testleri, gerçek PostgreSQL ve stub API'ye karşı arayüzle doğrulandı
  (2026-10-11/12) — **henüz gerçek Jenkins/OpenAI'a karşı değil**.

**Bilinen kısıtlar**
- Hatalar yalnızca konsol logundan ayrıştırılır: JVM çıktısı (Gherkin pretty çıktısı, Surefire/Failsafe ve Gradle
  test bazlı raporları, Java exception blokları). pytest / Jest / Cypress loglarından yalnızca test sayıları çıkar.
  Paralel koşuların birbirine karışan çıktısı bir exception'ı yanlış senaryoya bağlayabilir.
- Başarısız bir step'in altında, bir `Failed scenarios:` listesinde veya bir runner raporunda gösterilmeyen bir
  senaryo doğrulanamaz: bunların hiçbirinin olmadığı bir logda, geçen bir senaryonun logladığı exception onun hatası
  sayılır.
- Runner adları senaryolarla ada veya aynı hataya göre eşleştirilir; hata metni farklı, alışılmadık bir adlandırma
  şeması bir hatayı yine de iki kez saydırabilir.
- Jenkins'in JUnit `testReport` API'si kullanılmaz (bu ekibin job'ları `junit` değil Cucumber raporu yayınlar).
- Grup başına yalnızca temsilcinin stack trace'i ve log alıntısı saklanır, her başarısız test için değil.
- Büyük bir koşuda saklanan "Relevant Snippet"e `Scenario:` işaret satırları hâkim olur (`RelevantLogExtractor`
  değiştirilmedi); grup kartları her grubun kendi kanıtını taşır.
- Maskeleme kalıp tabanlıdır: Selenium'un yazdığı metin (`sendKeys … value=[…]`) maskelenmez; kimlik bilgisi gibi
  adlandırılmış bir JSON alanı bir doğrulama mesajı tutsa bile maskelenir.
- Başarısız bir çağrının token'ları ve maliyeti toplamlara dahil değildir (sağlayıcı onun için kullanım bildirmez).

**Sıradakiler**
- Grup prompt'unu ve cevap kalitesini kontrol etmek için bir kez gerçek bir çoklu hatalı build (ve isteğe bağlı
  canlı test) koşmak.
- **Bir Jenkins klasörü içindeki** bir job'ın gerçek build'ini analiz etmek — destekleniyor ve birim testli, ama
  gerçek Jenkins'e karşı hiç denenmedi.
- Uygulamanın ekibe nasıl sunulacağına karar vermek (ör. frontend build'i Spring'in `static/` klasöründe — o zaman
  `/analyses/:id` sayfa yenilemede `index.html`'e yönlendirme ister — ya da ayrı bir web sunucusu). Bugün iki
  geliştirme sunucusu olarak çalışıyor.
- Gerçek başarısız build'leri bir değerlendirme seti olarak toplamak; üçüncü aksiyonun hâlâ genel kaldığı yerlerde
  prompt'u sıkılaştırmak.
- Değişiklik bağlamı: build'in commit'leri/değişen dosyaları Jenkins API'sinden ve mümkünse
  `exceptionFile:exceptionLine` konumundaki kaynak satırı.
- İhtiyaç olunca daha fazla `InteractionExtractor` implementasyonu (Playwright, Cypress) ve başka LLM sağlayıcıları.

**V1 için reddedilenler (proje sahibi onaylamadan yeniden eklemeyin)**
- Kullanıcılar, projeler, proje üyeleri, pipeline'lar, roller, giriş (bir kez yapıldı, sonra kaldırıldı — V2
  migration).
- Kendi `whatHappened` alanıyla bir "NE OLDU?" arayüz bölümü (beklenen / gerçek / mekanizma) — şimdilik reddedildi
  (grup cevaplarındaki `rootCause` ne olduğunu, hangi koşulda ve hangi kanıta göre olduğunu söylüyor).
- Asenkron analiz, SSE/gerçek ilerleme olayları, önbellekleme, kuyruklar, ek DTO/mapper/repository katmanları,
  hatalar için yeni tablolar.
- Yapılandırılmış Kanıt (Structured Evidence) sekmesi ve 6 aşamalı ilerleme ekranı (daha basit V1 ekranlarıyla
  değiştirildi).

**Açık yan görevler**
- Eski Jenkins token'ını iptal etmek (bkz. Güvenlik).
