# PupsChat

Топовый чат-плагин для Paper 1.21.x

**[Скачать](https://github.com/nemallone/PupsChat/releases/latest)** - [config.yml](src/main/resources/config.yml)

## Возможности

1. Локальный, глобальный и стафф чат
2. Поддержка MiniMessage, HEX-цветов (`&#rrggbb`) и градиентов
3. Hover-текст у сообщений и команды по клику на них
4. Фильтры рекламы, спама, флуда, капса, слов, символов и токсичности
5. Накопительные нарушения и муты с эскалацией длительности
6. Уведомления персоналу о срабатываниях фильтров
7. Персональные и глобальные @упоминания
8. Подсказки, замена слов, авто-гг, объявления и скрытие чата
9. Логирование команд и сообщений всех игроков
10. Встроенный ИИ-фильтр токсичности без сети и API

## Требования

- Paper 1.21.x
- Java 21

Плагин softdepend на:

- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) - для плейсхолдеров в форматировании чата
- PremiumVanish и/или EssentialsX - игроки в ванише игнорируются в упоминаниях
- StrikePractice - доп. настройки для скрытия чата во время дуэли, и автосообщения после боя

## Установка

1. Скачайте [последнюю версию .jar плагина](https://github.com/nemallone/PupsChat/releases/latest)
2. Остановите сервер
3. Поместите плагин в директорию `plugins/`
4. Запустите сервер
5. Готово. Настроить конфиги плагина можно в директории `plugins/PupsChat`

## Команды

| Команда                      | Зачем                             | Permission             |
| ---------------------------- | --------------------------------- | ---------------------- |
| `/pupschat ai` | Состояние ИИ-фильтра | `pupschat.admin` |
| `/pupschat logs staff all [строк]` | Журнал staff-чата | `pupschat.logs.staff` |
| `/pupschat reload`           | Перезагрузить конфигурацию        | `pupschat.admin`       |
| `/pupschat logs (messages/commands) all [строк]` | Последние общие логи | `pupschat.logs.view` |
| `/pupschat logs (messages/commands) player <ник> [строк]` | Последние логи игрока | `pupschat.logs.view` |
| `/pupschat logs clear <старше дней>` | Удалить старые архивы и сессии | `pupschat.logs.clear` |
| `/unmutef <игрок>`           | Снять мут, выданный фильтром      | `pupschat.unmutef`     |
| `/mentions`                  | Включить или выключить упоминания | `pupschat.mentions`    |
| `/chathide`                  | Переключить видимость чата        | `pupschat.chat`        |
| `/automessage`               | Переключить сообщение после боя   | `pupschat.automessage` |
| `/acb <ник\|@a> <сообщение>` | Отправить сообщение в action bar  | `pupschat.acb`         |

Для команды `/pupschat` есть алиас `/pchat`

Примечание: текст `/acb` поддерживает MiniMessage. Для буквального символа `<` используйте
обратную косую черту, например `\<3>`

Список прав находится тут: [`plugin.yml`](src/main/resources/plugin.yml)

## Билдинг с сурсов

```
git clone https://github.com/nemallone/PupsChat.git
cd PupsChat
./gradlew build (.\gradlew build если вы на winduz🤮)
```

Готовый .jar появится в директории `build/libs/`

## Лицензия

[GNU GPLv3](LICENSE). Issues и pull requests можно оформлять на русском языке
