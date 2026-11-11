# Descargador X

App Android sencilla: pega el enlace de un tweet (X) y descarga sus imágenes, vídeos o GIFs a la galería.
También aparece en "Compartir" desde la app de X (rellena el enlace; eliges formato y pulsas Descargar).

## Formato de vídeos y GIFs
- **MP4**: el archivo original -> Películas/XDescargas
- **GIF**: primero se guarda el MP4 en la galería (Películas/XDescargas) y después se convierte a GIF animado en el propio móvil (máx. 20 s, ancho máx. 480 px, ~14 fps) -> Imágenes/XDescargas. Quedan guardados los dos.
- Las imágenes se guardan siempre como imagen -> Imágenes/XDescargas

Requiere Android 10 o superior.

## APK
Al subir el proyecto a GitHub (rama `main`), GitHub Actions compila el APK automáticamente.
Descárgalo desde **Releases -> Última versión** (o desde Actions -> Artifacts).
