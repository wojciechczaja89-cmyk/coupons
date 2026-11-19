# Kupony rabatowe

Serwis REST tworzy kupony i rejestruje ich użycia. Każdy kupon ma limit globalny, kraj i limit jednego użycia na identyfikator użytkownika.

## Uruchomienie

Wymagania lokalne to JDK 21, Maven 3.9 oraz Docker Compose do uruchomienia PostgreSQL. Z katalogu projektu wykonaj w jednej powłoce:

```sh
export DB_PASSWORD="$(openssl rand -hex 24)"
docker compose up -d --wait
mvn clean verify
java -jar target/coupons-1.0.0.jar
```

Zachowaj hasło do kolejnych uruchomień z tym samym wolumenem. Aplikacja nasłuchuje na porcie 8080. Flyway przy starcie tworzy schemat. Własną instancję PostgreSQL można wskazać przez `DB_URL`, `DB_USER` i `DB_PASSWORD`; wtedy krok z Compose jest zbędny.

## Przykład

```sh
curl -i localhost:8080/coupons -H 'Content-Type: application/json' \
  -d '{"code":"wiosna","maxUses":2,"country":"PL"}'
curl -i localhost:8080/coupons/WiOsNa/redemptions -H 'Content-Type: application/json' \
  -d '{"userId":"klient-42"}'
```

```powershell
$baseUrl = 'http://127.0.0.1:8080/coupons'
$kupon = @{
    code    = 'wiosna2'
    maxUses = 2
    country = 'PL'
} | ConvertTo-Json

Invoke-WebRequest -UseBasicParsing -Method Post -Uri $baseUrl -ContentType 'application/json' -Body $kupon | Select-Object StatusCode, Content
    
$uzycie = @{ userId = 'klient-43' } | ConvertTo-Json

Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$baseUrl/wiosna2/redemptions" -ContentType 'application/json' -Body $uzycie | Select-Object StatusCode, Content
```

Pierwsza odpowiedź ma status 201 i zawiera `code`, `createdAt`, `maxUses`, `country` oraz `uses`. Udane użycie zwraca 204 z pustym ciałem. Kody mają od 1 do 64 znaków ASCII (litery, cyfry, podkreślenie, łącznik). W bazie zapisujemy je wielkimi literami. Kraj podaje się jako dwuliterowy kod ISO 3166-1 alpha-2 wielkimi literami. Identyfikator użytkownika jest niepustym tekstem do 128 jednostek UTF-16, rozróżnia wielkość liter. Klient podaje go samodzielnie, zgodnie z założeniem o braku uwierzytelniania.

### Geolokalizacja podczas demonstracji

Domyślnie serwis wysyła IP drugiej strony połączenia do bezpłatnego API country.is. Adres lokalny nie identyfikuje kraju, więc drugi przykład na localhost może zwrócić 503. Aby przejść cały scenariusz lokalnie, uruchom w osobnym terminalu prosty odpowiednik dostawcy dla adresu pętli zwrotnej:

```sh
geo_dir=$(mktemp -d)
echo '{"country":"PL"}' > "$geo_dir/127.0.0.1"
(cd "$geo_dir" && python3 - <<'PYTHON'
from http.server import HTTPServer, SimpleHTTPRequestHandler

class GeoHandler(SimpleHTTPRequestHandler):
    def guess_type(self, path):
        return "application/json"

HTTPServer(("127.0.0.1", 9090), GeoHandler).serve_forever()
PYTHON
)
```

Ustaw `GEO_BASE_URL=http://127.0.0.1:9090` przed uruchomieniem aplikacji i wykonaj oba polecenia curl z adresem `127.0.0.1` zamiast `localhost`. W integracji produkcyjnej adapter oczekuje odpowiedzi JSON na `GET /{ip}`. Aplikacja ignoruje nagłówki przekazywane przez klienta. Za reverse proxy należy skonfigurować odczyt adresu wyłącznie od zaufanego pośrednika. Bez tego geolokalizacja dotyczy adresu proxy.

### Błędy

Błędy biznesowe mają postać `{"error":"LIMIT_REACHED"}`.

| HTTP | Kod | Znaczenie |
|---|---|---|
| 400 | INVALID_JSON, INVALID_CODE, INVALID_COUPON, INVALID_USER | Niepoprawne żądanie |
| 403 | WRONG_COUNTRY | Kraj IP różni się od kraju kuponu |
| 404 | UNKNOWN_CODE | Kupon nie istnieje |
| 409 | CODE_EXISTS | Kod jest już zajęty |
| 409 | ALREADY_USED | Ten użytkownik zrealizował kupon |
| 409 | LIMIT_REACHED | Wyczerpano pulę |
| 503 | COUNTRY_UNAVAILABLE | Nie można ustalić kraju. Można ponowić żądanie |

## Decyzje projektowe

Kontroler odpowiada za kontrakt HTTP. Interfejs GeoLookup oddziela zależność sieciową od rejestracji użycia. Zapytanie o kraj odbywa się przed transakcją, z limitami czasu połączenia i odczytu po dwie sekundy. Awaria dostawcy kończy próbę przed odczytem kuponu i przed zmianą stanu.

Do dwóch operacji wystarcza Spring JDBC. Jawny SQL pozwala sprawdzić granice transakcji i zakres blokady. PostgreSQL blokuje wiersz konkretnego kuponu przez SELECT FOR UPDATE. Po uzyskaniu blokady transakcja sprawdza kraj, rejestruje parę (kod, użytkownik), sprawdza limit i zwiększa licznik. Błąd wycofuje całość, także tymczasowy wpis dla wyczerpanego kuponu. Powtórzenie udanej operacji zwraca ALREADY_USED również po wyczerpaniu puli.

Wiele instancji aplikacji może współdzielić bazę. Próby dla tego samego kodu są szeregowane w bazie, a różne kupony mają osobne blokady. Wygrywa transakcja, która pierwsza uzyska dostęp do wiersza, bez gwarancji kolejności według chwili wysłania HTTP. Popularny pojedynczy kupon ogranicza przepustowość, ale krótka transakcja zapewnia ścisły limit. Klucz główny zabezpiecza unikalność kodu, klucz złożony zabezpiecza pojedyncze użycie, a CHECK ogranicza licznik.

## Testy

`mvn clean verify` uruchamia testy HTTP przez MockMvc, migrację Flyway i osobny proces PostgreSQL na losowym porcie. Wymagają konta bez uprawnień root (wymóg initdb). Baza testowa jest tymczasowa. Docker i klucz do geolokalizacji nie są potrzebne. Pierwszy build pobiera zależności i binaria PostgreSQL z Maven Central.

Dwa testy współbieżności synchronizują osiem żądań barierą: różni użytkownicy rywalizują o ostatnie użycie, a następnie jeden użytkownik wysyła równoległe próby dla kuponu z zapasem. Oba scenariusze sprawdzają odpowiedzi oraz licznik i liczbę wpisów w bazie. Pozostałe testy obejmują normalizację kodu, walidację, odmowy biznesowe i adapter geolokalizacji z lokalnym serwerem HTTP.

Raport pokrycia JaCoCo powstaje po fazie verify w `target/site/jacoco/index.html`.
