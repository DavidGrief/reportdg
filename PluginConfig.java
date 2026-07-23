name: ReportDG
version: 1.1.0
main: ru.teamworld.reports.ReportDGPlugin
api-version: '1.16'
author: DavidGrief
description: RGB-система репортов Minecraft → Discord с антифлудом, модерацией и ответами игрокам.
commands:
  report:
    description: Отправить жалобу на игрока в Discord
    usage: /report <ник> <причина>
    aliases: [rep, жалоба]
    permission: reportdg.report
  reportdgreload:
    description: Перезагрузить конфигурацию ReportDG
    usage: /reportdgreload
    aliases: [reportsreload, rdgreload, trreload]
    permission: reportdg.reload
permissions:
  reportdg.report:
    description: Разрешает отправлять репорты
    default: true
  reportdg.bypasscooldown:
    description: Отключает трёхчасовой антифлуд репортов на одного игрока
    default: op
  reportdg.notify:
    description: Показывает новые репорты администрации в Minecraft
    default: op
  reportdg.reload:
    description: Разрешает перезагрузку плагина
    default: op
  teamreports.report:
    description: Устаревший алиас права ReportDG
    default: false
    children:
      reportdg.report: true
  teamreports.bypasscooldown:
    description: Устаревший алиас права ReportDG
    default: false
    children:
      reportdg.bypasscooldown: true
  teamreports.notify:
    description: Устаревший алиас права ReportDG
    default: false
    children:
      reportdg.notify: true
  teamreports.reload:
    description: Устаревший алиас права ReportDG
    default: false
    children:
      reportdg.reload: true
