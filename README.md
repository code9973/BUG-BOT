# Bug Manager Bot v2.0

Telegram group bot for bug publishing, assignment, claiming, completion, undo, and daily reports.

## Commands

Activate the group first:

```text
激活
/activate
```

Task commands:

```text
发布 <描述>
/bug <描述>

派发 @username <描述>
/assign @username <描述>

派发 <编号> @username

领取 <编号>
/claim <编号>

完成 <编号>
/done <编号>

撤销 <编号>
/undo <编号>

清单
列表
/list

归档
/archive
```

## Behavior

- `发布` creates a pending task and anyone can claim it.
- `派发 @username` creates the task directly in progress and assigns it to that Telegram username.
- `派发 <编号> @username` assigns an existing pending task by task number.
- `撤销` reverses one state step:
  - `已完成 -> 处理中`
  - `处理中 -> 待领取`
- A directly assigned task can be completed or undone by the mentioned `@username`.

## Database

The `tasks` table stores:

- `seq`: per-group task number
- `id`: short UUID for callback buttons
- `owner_username`: Telegram username used for direct `@member` assignment
- `status`: `0=pending`, `1=in_progress`, `2=done`
- `claim_time`, `done_time`: timestamps for duration tracking

The app will auto-migrate older databases by adding `owner_username` if it is missing.

## Build

Typical Maven build:

```bash
mvn clean package -q
java -jar target/bug-manager-bot-2.0.0.jar
```

## Project Structure

```text
bug-bot/
├─ pom.xml
├─ bugs.db
└─ src/main/
   ├─ java/com/bugbot/
   │  ├─ BugManagerBot.java
   │  ├─ DatabaseManager.java
   │  ├─ MessageBuilder.java
   │  ├─ BotConfig.java
   │  └─ Task.java
   └─ resources/config.properties
```
