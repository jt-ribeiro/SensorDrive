#  SENSOR DRIVE

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Custom Engine](https://img.shields.io/badge/Engine-SurfaceView_Canvas-FF00FF?style=for-the-badge)

**Sensor Drive** é um *endless runner* arcade com estética *Retro-Futurista / Synthwave*, desenvolvido nativamente para Android. O jogo transforma o teu dispositivo móvel num volante real, utilizando sensores de hardware para criar uma experiência de condução imersiva e reativa.

Este projeto foi desenvolvido como Exercício Interativo para a Unidade Curricular de Tecnologias Interativas (ESTG-IPVC).

---

##  Funcionalidades Principais

* 📱 **Condução Cinemática (Giroscópio):** Utilização do sensor `TYPE_ROTATION_VECTOR` com implementação de um *Low-Pass Filter* algorítmico e uma *Deadzone* para garantir controlos super precisos e anular a trepidação natural das mãos.
* ⚡ **Nitro Tátil (Proximidade):** Integração inovadora do sensor `TYPE_PROXIMITY`. Basta passar a mão sobre o topo do ecrã para ativar o Nitro Manual!
* 🎧 **Áudio Estéreo Reativo (3D Panning):** O som do motor desloca-se dinamicamente entre os altifalantes esquerdo e direito consoante a inclinação física do telemóvel.
* 👾 **Dificuldade Dinâmica:** O jogo acelera e aumenta a frequência de obstáculos algorítmicamente à medida que a distância aumenta.
* 🧠 **Obstáculos Cognitivos:** Cones roxos tóxicos invertem temporariamente os eixos de controlo, forçando o jogador a adaptar os seus reflexos em frações de segundo.
* 🚀 **Motor Gráfico Customizado:** Construído de raiz em `SurfaceView` com *Threads* dedicadas e pré-alocação de memória (evitando *Garbage Collection Lag*), garantindo **60 FPS** cravados.

---

##  Screenshots

*(Substitui os links abaixo pelas tuas próprias capturas de ecrã quando subires para o GitHub)*

<p float="left">
  <img src="link_para_imagem_do_menu.png" width="30%" />
  <img src="link_para_imagem_do_jogo_1.png" width="30%" />
  <img src="link_para_imagem_do_jogo_2.png" width="30%" />
</p>

---

##  Como Jogar

1. **Direção:** Segura o telemóvel na vertical (Portrait) e inclina-o para a **Esquerda** ou **Direita** para desviar.
2. **Nitro:** Aproxima a tua mão da câmara frontal (Sensor de Proximidade) para ganhar um *boost* massivo de velocidade, gastando a tua barra de energia.
3. **Estrelas (Bónus):** Apanha as estrelas para ganhares 5 segundos de Nitro infinito e invulnerabilidade de velocidade!
4. **Cones Normais (Laranja):** Evita-os a todo o custo. Retiram-te 1 vida (raio de energia) e emitem um flash vermelho.
5. **Cones de Confusão (Roxos):** Se bateres num, o *pitch* do áudio distorce e os teus **controlos invertem** durante 3 segundos!

---

##  Arquitetura de Software

O projeto segue estritamente o princípio de **Separation of Concerns (SoC)** para garantir código limpo e escalável:

* `GameView.kt`: O coração do motor. Focado exclusivamente no *Game Loop*, física matemática e *rendering pipeline*.
* `GameConstants.kt`: Isolamento de todas as configurações do jogo, velocidades e paleta de cores Neon (convertidas para hexadecimais nativos para acesso em `O(1)` na memória).
* `GameEntities.kt`: Classes de Dados (Data Classes) puras que definem os modelos do jogo (Veículos, Cones, Partículas e Popups).
* `MainActivity.kt` & `MenuActivity.kt`: Gestão do ciclo de vida Android, interceção dos sensores de hardware e gestão do *SoundPool* e *MediaPlayers*.

---

##  Instalação e Compilação

Para correres este projeto localmente na tua máquina:

1. Clona o repositório:
   ```bash
   git clone [https://github.com/TEU-USER/SensorDrive.git](https://github.com/TEU-USER/SensorDrive.git)
