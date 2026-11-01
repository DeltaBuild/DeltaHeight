⚠️ Ce dépôt est obsolète. Retrouvez la nouvelle version ici : https://github.com/DeltaBuild/DeltaAgent

# DeltaHeight

Javaagent pour serveur MCPC+ 1.6.4 qui étend la hauteur du monde de Y -64 à Y 319 (24 sections au lieu de 16), sans toucher au jar du serveur.

Le protocole réseau est celui du client Minecraft NGClient, les joueurs doivent l'utiliser pour se connecter.

## Prérequis

- MCPC+ 1.6.4 build 164-251 (Forge 9.11.1.965). La classe `Chunk` est compilée pour ce build, les autres builds de MCPC+ et Cauldron ne sont pas pris en charge.
- Java 17 avec un agent qui fait tourner MCPC+ sous Java 17 (testé avec [mcpc-java17-agent](https://github.com/Loyto-SKHR/mcpc-java17)).
- Le client NGClient pour les joueurs. Un client 1.6.4 classique ne sait pas lire les chunks en 24 sections et se fait déconnecter.

## Installation

1. Faire une sauvegarde de la map avant le premier lancement.
2. Copier `DeltaHeight.jar` dans le dossier du serveur.
3. Ajouter l'agent dans la commande de lancement, avant `-jar` :

   ```
   java -javaagent:mcpc-java17-agent.jar -javaagent:DeltaHeight.jar -Xmx4G -jar mcpc.jar nogui
   ```

Au démarrage, la console doit afficher :

```
[DeltaHeight] DeltaHeight 1.0.0 : monde étendu Y -64..319 (24 sections), protocole NGClient (marqueur NGNEID)
[DeltaHeight] transformer installé après cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer ...
[DeltaHeight] remplacement de net.minecraft.world.chunk.Chunk (24 sections, Y -64..319)
```

Si ces lignes n'apparaissent pas, l'agent n'est pas chargé. Une map qui a des blocs sous Y 0 ou au-dessus de Y 255 ne doit jamais tourner sans DeltaHeight, sinon le serveur n'arrive pas à lire ces chunks et les régénère.

`max-build-height=256` dans `server.properties` passe à 320.

## Format de sauvegarde

Toujours du format Anvil, avec le même principe que la 1.18 :

- le tag `Y` des sections est signé et va de -4 à 19
- la heightmap contient des Y absolus (-64 = colonne vide)
- un tag `DeltaHeight` marque les chunks enregistrés au format étendu

Les maps 1.6.4 existantes se chargent sans conversion, rien ne change entre Y 0 et Y 255.

Pour revenir en arrière : tant qu'aucun bloc n'a été posé hors de 0..255, la map reste lisible sans DeltaHeight. Après, il faut remettre la sauvegarde.

## Protocole réseau

Ce qui change par rapport à la 1.6.4 vanilla (compatible NGClient) :

- marqueur `Packet250` sur le canal `NGNEID`, envoyé juste après `Packet1Login`
- Packet51 / Packet56 : masques de sections en `int` (24 bits) au lieu de `short`
- Packet14 / 15 / 53 : Y en `short` signé au lieu d'un octet
- Packet52 : envoyé seulement si tous les Y sont entre 0 et 255, sinon remplacé par des Packet53
- Packet61 (sons et particules) : pas envoyé si Y est hors de 0..255

## Fonctionnement

L'agent se place juste après la déobfuscation de FML, les coremods (et l'agent Java 17) passent donc par-dessus normalement. Les principales modifications :

- `Chunk` est remplacée (24 sections, heightmap, lumière et entités de -64 à 319)
- `AnvilChunkLoader` lit et écrit les 24 sections
- `World` et quelques autres classes passent leurs limites de 0..255 à -64..319, collisions comprises
- les paquets réseau listés plus haut
- l'API Bukkit (`CraftWorld`, `CraftChunk`, `CraftBlock`, `ChunkSnapshot`) fonctionne sur -64..319

Les plugins WorldEdit 5.6 (et AsyncWorldEdit) et VoxelSniper 5.168 sont aussi patchés au chargement pour accepter les Y négatifs. Avec une autre version, si le code attendu n'est pas trouvé, la classe est laissée telle quelle et la console le signale.

## Superflat

Dans les mondes superflat, les nouveaux chunks ont une bedrock à Y -64 et de la pierre de -63 à 0. Les couches du preset ne bougent pas (par défaut 2 de terre et l'herbe à Y 3). Les chunks déjà générés ne sont pas modifiés.

## Sécurités

Si une modification importante échoue (stockage, sauvegarde, protocole) ou si `Chunk` n'a pas été remplacée, le serveur s'arrête directement plutôt que de tourner à moitié et d'abîmer la map.

## Options

- `-Ddeltaheight.debug=true` : affiche la liste des transformers au démarrage
- `-Ddeltaheight.legacyView=pkg.Classe1,pkg.Classe2` : voir plus bas

### legacyView

Avec DeltaHeight, le tableau de sections d'un chunk (`getBlockStorageArray()`) a 24 cases et la section Y 0 est à l'index 4. Un mod fait pour la 1.6.4 classique qui lit ce tableau avec `sections[y >> 4]` tape donc 64 blocs trop bas, voire en dehors du tableau.

Les classes passées dans cette option reçoivent à la place une vue de 16 sections (Y 0 à 255) avec l'ancien index. Elles ne voient pas ce qui est hors de 0..255 mais marchent comme avant.

C'est déjà fait pour les générateurs superflat et Bukkit, `ChunkHelper` (NGUpgrades) et `MovementUtility` (Calclavia). L'option sert seulement si un autre mod pose problème.

## Limites connues

- Seul le `WorldProvider` de base est modifié, une dimension de mod qui redéfinit `getHeight()` garde sa hauteur.
- WorldEdit `//regen` ne régénère pas la partie sous Y 0.
- Packet17 (lit) et Packet61 (effets) ne sont pas envoyés hors de 0..255, le client lit Y sur un octet.

## Compiler

Il faut un JDK 17. Le build ne télécharge rien d'autre que Gradle et n'a pas besoin du jar du serveur :

```
./gradlew build
```

Le jar sort dans `build/libs/DeltaHeight.jar`. La version (1.0.0 par défaut) se change dans `build.gradle`.

`Chunk` est compilée contre `libs/mcpc-stubs.jar`, qui contient seulement les signatures des classes MCPC+ utilisées (pas de code). Si `Chunk.java` utilise de nouvelles classes ou méthodes de MCPC+, il faut le régénérer :

1. copier le jar MCPC+ du serveur dans `libs/minecraft_server.jar`
2. générer `libs/mcpc-srg.jar` (commandes en haut de `tools/Deobf.java`)
3. lancer `gradlew mcpcStubs`

Ces deux jars contiennent du code Minecraft, ils sont dans le `.gitignore` et ne doivent pas être publiés.

### GitHub Actions

Le projet est compilé à chaque push et pull request. Pour sortir une version, il suffit de pousser un tag :

```
git tag v1.0.0
git push origin v1.0.0
```

Le jar est construit avec la version du tag et publié dans une release.

### Arborescence

```
src/main/java/      agent, transformer et patchs ASM
src/replace/java/   classe Chunk de remplacement
libs/               ASM 6.0, launchwrapper 1.8, stubs MCPC+
tools/              Deobf et Stubs
```

## Licence

DeltaHeight est sous licence MIT (voir `LICENSE`) : chacun peut le forker, le modifier et le redistribuer en gardant la notice.

Le jar embarque ASM 6.0 (licence BSD 3-Clause, voir `licenses/ASM-LICENSE.txt`). `Chunk.java` est dérivé du code du serveur Minecraft / MCPC+ et `libs/mcpc-stubs.jar` contient des signatures de classes MCPC+, ces parties ne sont pas couvertes par la licence MIT.
