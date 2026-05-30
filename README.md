# Music Server

Ktor backend для MusicApp

## Зависимости

- JDK 17+ (Gradle нужна JVM ≥17; код компилируется в байткод JVM 17)
- Docker + Docker Compose (для Postgres)

## Запуск

### 1. Поднять Postgres

```bash
cd server
docker compose up -d
```

Контейнер: `musicapp-postgres`, порт `5432`, БД `musicapp`,
пользователь `music`, пароль `music`. Данные хранятся в volume `musicapp-data`.

### 2. Запустить сервер

С демо-треками из репо (ничего настраивать не надо):
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
./gradlew run
```

Со своей коллекцией:
```bash
MUSIC_DIR="/Users/kvltyapka/Music/MyMusic" ./gradlew run
```

При старте сервер:
1. подключается к Postgres,
2. через `SchemaUtils.create` создаёт все недостающие таблицы,
3. ищет каталог музыки: `MUSIC_DIR` → `./music/sample` (бандл в репо) → ничего,
4. делает upsert треков/альбомов (по `audio_url` / `(artist,title)`),
5. если каталог не найден совсем — наполняет БД дефолтным сидом из 10 треков (без аудио).

## Переменные окружения

| Переменная | Значение по умолчанию | Назначение |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/musicapp` | JDBC URL Postgres |
| `DATABASE_USER` | `music` | Пользователь БД |
| `DATABASE_PASSWORD` | `music` | Пароль БД |
| `MUSIC_DIR` | `./music/sample` | Папка с аудио-файлами. Если переменная не задана или путь не существует — используются демо-треки в репо. |
| `PORT` | `8080` | Порт HTTP-сервера |

## Схема БД


| Таблица | Описание |
|---|---|
| `users` | Зарегистрированные пользователи (email уникален; колонка `role` ∈ {`USER`,`ADMIN`}) |
| `albums` | Альбомы; уникальная пара `(artist, title)` |
| `tracks` | Треки; FK на album, уникальный `audio_url` |
| `playlists` | Плейлисты пользователей; FK на user |
| `playlist_tracks` | Junction-таблица плейлист ↔ трек (PK составной) |
| `favorites` | Junction-таблица user ↔ favorite track (PK составной) |

Все внешние ключи имеют `ON DELETE CASCADE`.

---

## Авторизация и роли

Аутентификация — JWT (HMAC256). Токен живёт 24 часа, в claims лежат `userId` и `role`.

### Роли

| Роль | Доступ |
|---|---|
| `USER` (по умолчанию при регистрации) | `/api/tracks`, `/api/albums`, `/api/playlists`, `/api/favorites`, `/audio/*` |
| `ADMIN` | всё что USER + полный `/api/admin/*` |

### Bootstrap-админ

При самом первом старте, если таблица `users` пустая, сервер автоматически создаёт:

```
email:    admin@music.app
password: admin123
role:     ADMIN
```

### Admin API

Все требуют заголовок `Authorization: Bearer <token>` от ADMIN-юзера.
Если токен USER-а — вернётся `403 Forbidden`.

| Метод | Путь | Что делает |
|---|---|---|
| `GET` | `/api/admin/stats` | Счётчики: пользователи, треки, альбомы, плейлисты |
| `GET` | `/api/admin/users` | Список юзеров (без `password_hash`) |
| `DELETE` | `/api/admin/users/{id}` | Удалить юзера (запрещено удалять себя) |
| `DELETE` | `/api/admin/tracks/{id}` | Удалить трек (каскадом — из плейлистов и избранного) |
| `DELETE` | `/api/admin/albums/{id}` | Удалить альбом (каскадом — все треки альбома) |
| `POST` | `/api/admin/rescan` | Пересканировать `MUSIC_DIR` и сделать upsert |

