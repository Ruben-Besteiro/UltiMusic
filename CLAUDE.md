Esto es UltiMusic, el reproductor de música definitivo que es libre de las restricciones de MediaStore.

La aplicación tendrá un editor de metadatos para canciones, álbumes y artistas que usará los scripts de modelos. Lo que el usuario ponga dentro de la aplicación sobrescribe lo que había en MediaStore de antemano, y ese será el único caso donde utilicemos MediaStore.

Los campos og\* del modelo de canciones son para remixes, donde estos campos guardan información de la canción original. Pero al ser campos secundarios, no creamos perfil de artista ni de álbum ni nada de eso.


Cuando termines de escribir código, quiero que compiles la aplicación por si hay algún error de compilación, pero yo me encargo de verificar que todo funciona. Esto es para ahorrar tiempo y tokens.

Todos los elementos que sean de color amarillo o amarillo oscuro deben usar el color dinámico.

Al hacer ediciones dentro de la aplicación, lo nuevo debe aparecer instantáneamente a lo largo de toda la aplicación, no se puede ver lo antiguo.

Al tocar la base de datos, se debe crear una migración de BD y destruir la anterior.

Cuando se vaya a tocar un string, ese cambio se debe aplicar a todos los idiomas

El menú de tres puntos de la selección múltiple de canciones debe ser idéntico al de una canción individual (menu\_song\_item.xml), pero aplicándose a todas las seleccionadas. Cualquier opción nueva del menú individual debe añadirse también a los menús de selección (menu\_song\_selection.xml y menu\_queue\_selection.xml) y viceversa. Únicas excepciones: "Ir al álbum/artista", que no tiene sentido con varias canciones.

Para hacer ediciones utiliza Edit a no ser que tenga alguna desventaja importante (explícala), para que así salgan los diffs en la terminal.

No hagas commits, pushees ni escribas títulos ni descripciones a no ser que te diga que lo hagas