discord:
  enabled: true
  bot-token: "PASTE_BOT_TOKEN_HERE"

  report-channel-id: "PASTE_REPORT_CHANNEL_ID"

  rejected-channel-id: "PASTE_REJECTED_CHANNEL_ID"

  staff-role-ids:
    - "PASTE_STAFF_ROLE_ID"

  staff-user-ids: []

  allow-any-discord-user: false

  server-name: "TeamWorld"

  colors:
    new-report: "#FCD05C"
    accepted: "#57F287"
    rejected: "#ED4245"

report:
  same-target-cooldown-seconds: 10800

  min-reason-length: 3
  max-reason-length: 300

  max-reply-length: 1000

  require-target-online: false
  allow-self-report: false

minecraft-staff-notify:
  enabled: true
  permission: "reportdg.notify"

messages:
  prefix: "&#FCD05C⛨ &#FFFFFF| "
  usage: "{prefix}&#FFFFFFИспользование: &#AE67F6/report <ник> <причина>"
  no-permission: "{prefix}&#FF5555У вас нет прав."
  players-only: "{prefix}&#FF5555Команда доступна только игрокам."
  cooldown: "{prefix}&#FFFFFFВы уже отправляли репорт на &#AE67F6{target}&#FFFFFF. Повторно можно через &#FCD05C{time}&#FFFFFF."
  target-offline: "{prefix}&#FF5555Игрок {target} не найден."
  self-report: "{prefix}&#FF5555Нельзя отправить репорт на самого себя."
  reason-short: "{prefix}&#FF5555Причина слишком короткая."
  reason-long: "{prefix}&#FF5555Причина слишком длинная. Максимум: {max} символов."
  sending: "{prefix}&#FFFFFFОтправляю репорт в Discord..."
  sent: "{prefix}&#FFFFFFРепорт &#FCD05C#{number} &#FFFFFFна игрока &#AE67F6{target} &#FFFFFFотправлен."
  failed: "{prefix}&#FF5555Не удалось отправить репорт в Discord. Сообщите администрации."
  accepted: "{prefix}&#FFFFFFВаш репорт &#FCD05C#{number} &#FFFFFFпринял администратор &#AE67F6{admin}&#FFFFFF."
  rejected: "{prefix}&#FFFFFFВаш репорт &#FCD05C#{number} &#FFFFFFотклонил администратор &#AE67F6{admin}&#FFFFFF."
  admin-reply: "{prefix}&#FCD05CОтвет администратора &#AE67F6{admin} &#FFFFFFпо репорту &#FCD05C#{number}&#FFFFFF: &#AE67F6{reply}"
  staff-new-report: "{prefix}&#FCD05C{reporter} &#FFFFFFотправил репорт на &#AE67F6{target} &#FFFFFFпо причине: &#FCD05C{reason}"
  reloaded: "{prefix}&#55FF55Конфигурация ReportDG перезагружена."
