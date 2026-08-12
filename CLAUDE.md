Esto es UltiMusic, el reproductor de música definitivo que es libre de las restricciones de MediaStore.



La aplicación tendrá un editor de metadatos para canciones, álbumes y artistas que usará los scripts de modelos. Lo que el usuario ponga dentro de la aplicación sobrescribe lo que había en MediaStore de antemano, y ese será el único caso donde utilicemos MediaStore.



Los campos og\* del modelo de canciones son para remixes, donde estos campos guardan información de la canción original. Pero al ser campos secundarios, no creamos perfil de artista ni de álbum ni nada de eso.



No hagas verificaciones automáticas después de hacer cambios. Solo dime qué debo hacer y lo haré yo. Así iremos más rápido y consumiremos menos tokens.



Todo lo referente a los artistas y a los productores se hará de la misma manera, puesto que son cosas similares. Cuando se toque uno de los dos, se debe intentar evitar inconsistencias.



Todos los elementos que sean de color amarillo o amarillo oscuro deben usar el color dinámico.



Al editar una imagen, la imagen nueva debe aparecer instantáneamente a lo largo de toda la aplicación, no se puede ver la imagen antigua (salvo en la notificación)

