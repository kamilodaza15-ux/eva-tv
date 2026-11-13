# EVA TV (Android TV)

App de TV para tu Android TV con la lista gratuita de canales de Colombia (iptv-org).

## Compilar (una sola vez, en un PC)
1. Instala Android Studio (gratis).
2. Abre la carpeta `evatv` (File > Open). Espera a que termine el "Sync" (descarga cosas solo).
3. Menu: Build > Build Bundle(s) / APK(s) > Build APK(s).
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar en el TV (lo mas facil)
- Instala en el TV la app **Send Files to TV** (y en el celular tambien), y manda el APK por WiFi.
- O pasa el APK en una USB y abrelo con un explorador de archivos en el TV.
- Si el TV pide permiso para "fuentes desconocidas", activalo para esa app.

## Control remoto
- Al abrir: menu principal con dos botones, Canales y Peliculas
- En una lista: OK reproduce el canal; sube hasta "Buscar..." para buscar en todo
- Mientras ves un canal: flecha arriba / abajo cambia de canal; OK o izquierda abre la lista; Menu abre el menu principal
- Atras: sube un nivel (lista -> menu -> salir)

## Personalizar
- Nombre: `app/src/main/res/values/strings.xml` (app_name)
- Lista de canales: `playlist_url` en el mismo archivo
- Tus propios canales: `app/src/main/res/raw/mis_canales.m3u` (salen primero)
- Logo y banner: `res/mipmap-xxxhdpi/ic_launcher.png` y `res/drawable-xhdpi/banner.png` (banner 640x360)
- Colores: `activity_main.xml` y `selector_item.xml`

La app guarda la ultima lista descargada, asi que si se va el internet al abrir, usa la guardada.
