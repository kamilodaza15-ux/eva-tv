# EVA TV (Android TV)

App de TV para tu Android TV con canales gratuitos (iptv-org). Pantalla: menu a la izquierda,
video al centro y lista de canales con logos a la derecha.

## Compilar
Se compila solo en GitHub (Actions) cada vez que se sube un cambio. El APK queda en
Releases > latest > EVA-TV.apk (enlace fijo).

## Control remoto
- Izquierda: menu (Canales, Peliculas, Series, Kids, Animacion, Documentales, Noticias, Musica, Favoritos, Buscar)
- Derecha: lista de canales. OK reproduce en la ventana del centro; OK otra vez (o OK en la imagen) = pantalla completa
- Pantalla completa: arriba / abajo cambia de canal; OK, izquierda o Atras vuelve al menu
- Favoritos: boton Menu del control (o OK largo) sobre un canal
- Buscar: opcion "Buscar" del menu o el boton de busqueda del control

## Personalizar
- Nombre y lista de Colombia: app/src/main/res/values/strings.xml
- Tus propios canales: app/src/main/res/raw/mis_canales.m3u (salen primero en Canales)
- Otras secciones: lista `sources` en MainActivity.kt
- Logo y banner: res/drawable-nodpi/logo_header.png, res/drawable-xhdpi/banner.png, res/mipmap-xxxhdpi/ic_launcher.png
