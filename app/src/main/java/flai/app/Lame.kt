package flai.app

object Lame {
    init {
        System.loadLibrary("lamejni")
    }

    external fun init(rate: Int, channels: Int, brate: Int)
    external fun encode(pcm: ByteArray): ByteArray?
    external fun flush(): ByteArray?
}
