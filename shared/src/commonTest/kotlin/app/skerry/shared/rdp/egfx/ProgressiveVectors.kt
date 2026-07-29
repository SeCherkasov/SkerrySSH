package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.hex

/**
 * Progressive streams this project did not write, and the pixels a decoder that is not ours makes of
 * them.
 *
 * MS-RDPEGFX prints no annotated dump for this codec the way it does for ClearCodec, and Microsoft's
 * own progressive sample data is available under NDA only — which is why FreeRDP's test for the same
 * codec ships without vectors. What is available is the implementation upstream checks against that
 * sample data: FreeRDP 3.30. Every entropy payload here came out of its encoder and every expected
 * pixel out of its decoder, so no payload and no expected pixel repeats Skerry's reading of the
 * specification. The block framing around the payloads is Skerry's reading, for everything except
 * [PICTURE]; FreeRDP decoding those streams is what says the framing is right.
 *
 * - [PICTURE] is FreeRDP's encoder output for [sourcePicture]. It uses the classic wavelet layout,
 *   and decoding it has to give the picture back — the one assertion that needs no oracle at all.
 * - [EXTRAPOLATED] is that same stream with three bytes changed: the context flag, the frame index,
 *   and the region flag that turns on RFX_DWT_REDUCE_EXTRAPOLATE. Same planes, same quantization
 *   factors, the layout every Windows server encodes and FreeRDP's own encoder never writes.
 * - [DENSE] puts a coefficient on every sub-band boundary of both layouts, under factors that differ
 *   between neighbours. An encoder leaves runs of zeroes there, so a picture cannot show a boundary
 *   that sits one sample out; this can.
 * - [UPGRADE_FIRST] establishes a tile four bits coarse, and the two refinements after it carry two
 *   of those bits each — fixed pseudo-random ones, chosen by nothing that reads the decoder.
 *
 * Each tile is checked twice: `_SAMPLE` is every eighth pixel in both directions, which names a
 * pixel when it fails, and `_FINGERPRINT` is [fingerprint] over all four thousand of them, so that
 * nothing hides between the sampled ones.
 *
 * Regenerating any of this means running FreeRDP with `primitives_set_hints(PRIMITIVES_PURE_SOFT)`.
 * Its SIMD colour transform rounds a unit differently from the reference one, and these pixels are
 * exact against the reference.
 */
internal object ProgressiveVectors {

    /** The 64x64 picture handed to FreeRDP's encoder: flat areas, an edge, and a gradient. */
    fun sourcePicture(): IntArray = IntArray(64 * 64) { index ->
        val x = index % 64
        val y = index / 64
        when {
            y >= 40 -> argb(red = (x * 4) and 0xFF, green = 0x40, blue = 0x90)
            x in 20..43 && y in 8..23 -> argb(red = 0xE8, green = 0xE8, blue = 0xE8)
            else -> argb(red = 0x20, green = 0x30, blue = 0x50)
        }
    }

    /**
     * The fold behind every `_FINGERPRINT`: opaque ARGB pixels in row order, starting at zero.
     * It lives here so that a regenerated tile and the constant that describes it stay together.
     */
    fun fingerprint(pixels: IntArray): Int = pixels.fold(0) { fold, pixel -> fold * 31 + pixel }

    private fun argb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun samples(dump: String): List<Int> =
        dump.trim().split(Regex("\\s+")).map { it.toLong(16).toInt() }

    /** FreeRDP's encoder output for [sourcePicture], classic layout, one 64x64 tile. */
    val PICTURE = hex(
        """
        c0cc0c000000caacccca0001c3cc0a00000000400000c1cc0c00000000000000
        0100c4cc1d0300004001000100000100fe020000000000004000400066667788
        98c5ccfe02000000000000000000007601b300bf000000005a3727ff937adf6b
        6c9f6b6c9e6b4c9e6b4c9e6b4c9e6b4c9e8b509c1600078c203981dc1dc19c33
        8ce6733b3df7e21000001ce103302704e1389c4e4f870e14542000001ae10441
        10888924adb6ddddd999bbbbbbbce739000001d412e0f392c03fcb0bb27ff601
        91ee1287f302438c91bc6111c6901fd610027f34800d98004aa000251f0b8f1e
        1614747ffffa7f378de3c7d6680003030999a90000cec3fec7e67194f2763800
        01a34956648000ac8b2d96f7dfbbe6f7c73cf5000201249492400012ad74e76a
        a0fd60fe89872d63fe77ff2019ffd037ff310004d00798007002001c9d0a9c8f
        fffffffffffffffffffffffff05d18c0800000005000703438020000081d44f5
        20700001006e9ac4019eb8cf5e1ca01119a1290000a99ac7a0115447fff07c80
        3a110f077fffffffffffffffffffd2002002a1a814029b00e000207e28322189
        8b00041bfea8bc73fa928000207c80688c78c02040a200660ec73196a52dff9a
        924924924dfed512512922980000268ae466e6c5c4c5c4c5c4c5c4c5c4c5c002
        084010040202040821118000eb4141428a52aaa00035e8341a1a34d2aaaaaa02
        900001d4282e8968bd25a5eca6e242601d940d06848489000e687474cb2c400a
        fa3d1e9966dab08d80007110a93494e8ea4c4901b3e7caf4f7c072a0beffbd00
        8430fffee72f244444240070d0ab0041ffffa6213221f500bfb07fff04063ffc
        903f90c000ac0128584a427ffff875757bbffffe0b4ad2b9cffec5a52b4ce600
        0026cac476c6e584e584e584e584e584e58002094050140a0a1428a5550001d4
        80808104211b6c00035a8140408209555ddd77776eeef39ce6730000003a5882
        844c8a64c94e76a0487cf8f1e1615a34aab800d90a1452f002ee8342855befef
        77cf79ebbf10000a080492500000db10a914b50a9b3b3bc57be59a0c6809bf70
        05547ffee1f7cfe7cf58c204d095000018821d4aa02a530ff867f4047fff0cb9
        9001182d3cb75d77f7eb4db49a4a3ff4c08502140851bf9281e81020408c00c2
        cc06000000
        """,
    )

    /** [PICTURE] with the extrapolated layout turned on: the same planes, the same factors. */
    val EXTRAPOLATED = hex(
        """
        c0cc0c000000caacccca0001c3cc0a00000000400001c1cc0c00000001000000
        0100c4cc1d0300004001000100010100fe020000000000004000400066667788
        98c5ccfe02000000000000000000007601b300bf000000005a3727ff937adf6b
        6c9f6b6c9e6b4c9e6b4c9e6b4c9e6b4c9e8b509c1600078c203981dc1dc19c33
        8ce6733b3df7e21000001ce103302704e1389c4e4f870e14542000001ae10441
        10888924adb6ddddd999bbbbbbbce739000001d412e0f392c03fcb0bb27ff601
        91ee1287f302438c91bc6111c6901fd610027f34800d98004aa000251f0b8f1e
        1614747ffffa7f378de3c7d6680003030999a90000cec3fec7e67194f2763800
        01a34956648000ac8b2d96f7dfbbe6f7c73cf5000201249492400012ad74e76a
        a0fd60fe89872d63fe77ff2019ffd037ff310004d00798007002001c9d0a9c8f
        fffffffffffffffffffffffff05d18c0800000005000703438020000081d44f5
        20700001006e9ac4019eb8cf5e1ca01119a1290000a99ac7a0115447fff07c80
        3a110f077fffffffffffffffffffd2002002a1a814029b00e000207e28322189
        8b00041bfea8bc73fa928000207c80688c78c02040a200660ec73196a52dff9a
        924924924dfed512512922980000268ae466e6c5c4c5c4c5c4c5c4c5c4c5c002
        084010040202040821118000eb4141428a52aaa00035e8341a1a34d2aaaaaa02
        900001d4282e8968bd25a5eca6e242601d940d06848489000e687474cb2c400a
        fa3d1e9966dab08d80007110a93494e8ea4c4901b3e7caf4f7c072a0beffbd00
        8430fffee72f244444240070d0ab0041ffffa6213221f500bfb07fff04063ffc
        903f90c000ac0128584a427ffff875757bbffffe0b4ad2b9cffec5a52b4ce600
        0026cac476c6e584e584e584e584e584e58002094050140a0a1428a5550001d4
        80808104211b6c00035a8140408209555ddd77776eeef39ce6730000003a5882
        844c8a64c94e76a0487cf8f1e1615a34aab800d90a1452f002ee8342855befef
        77cf79ebbf10000a080492500000db10a914b50a9b3b3bc57be59a0c6809bf70
        05547ffee1f7cfe7cf58c204d095000018821d4aa02a530ff867f4047fff0cb9
        9001182d3cb75d77f7eb4db49a4a3ff4c08502140851bf9281e81020408c00c2
        cc06000000
        """,
    )

    /** A first pass four bits coarse in the level-3 bands and the low-pass image. */
    val UPGRADE_FIRST = hex(
        """
        c0cc0c000000caacccca0001c3cc0a00000000400001c1cc0c00000001000000
        0100c4ccf60100004001000103010100a7010000000000004000400066666666
        6600444400000044440000004444000000012222000000222200000022220000
        0002000000000000000000000000000000c6cca7010000000000000000000000
        c800640064000000005a3727ff937adf6b6c9f6b6c9e6b4c9e6b4c9e6b4c9e6b
        4c9e8b509c1600078c203981dc1dc19c338ce6733b3df7e21000001ce1033027
        04e1389c4e4f870e14542000001ae1044110888924adb6ddddd999bbbbbbbce7
        39000001d412e0f392c03fcb0bb27ff60191ee1287f302438c91bc6111c6901f
        d610027f34800d98004aa000251f0b8f1e1614747ffffa7f378de3c7d6680003
        030999a90000cec3fec7e67194f276380001a34956648000ac8b2d96f7dfbbe6
        f7c73cf5000201249492400012ad74e700268ae466e6c5c4c5c4c5c4c5c4c5c4
        c5c002084010040202040821118000eb4141428a52aaa00035e8341a1a34d2aa
        aaaa02900001d4282e8968bd25a5eca6e242601d940d06848489000e687474cb
        2c400afa3d1e9966dab08d80007110a93494e8ea0026cac476c6e584e584e584
        e584e584e58002094050140a0a1428a5550001d480808104211b6c00035a8140
        408209555ddd77776eeef39ce6730000003a5882844c8a64c94e76a0487cf8f1
        e1615a34aab800d90a1452f002ee8342855befef77cf79ebc2cc06000000
        """,
    )

    /** The refinement that carries the first two of those bits. */
    val UPGRADE_SECOND = hex(
        """
        c0cc0c000000caacccca0001c3cc0a00000000400001c1cc0c00000001000000
        0100c4ccd1010000400100010301010082010000000000004000400066666666
        6600444400000044440000004444000000012222000000222200000022220000
        0002000000000000000000000000000000c7cc82010000000000000000000160
        0018006000180060001800c67e816b4bfbe2fb54f6bddf7c1ce18701bf31de56
        720f4767668759aa883c59ea56137bd285a1d83c54552f37ae655bda027998cc
        e31a768e5fd9998f1f3f36ee43784d0dfabea6dae4868edc296d4eff56e17020
        fb8fb1580590c509dc53cd85d86bac9896f7a619122ddf400bf515ae80e1a715
        eeb13b8c21ff72edd718d94e139513dc1b63fc9306f6bf9ce506e06db00a059f
        f275878e34b3bcb32be202c0a1518c8023b9ec6d6f3d640e9c23ec170750033f
        018536df3a5c714fec000900c7af8559a0f13053d8955fd38d7082ca83d5ed0f
        d1d3644b7be9b339732d84132e0513a10977893fc7a6885b61a8d453c37d788e
        b44db7482f6d463d19e570244cbba0e358fc7874fa8cb1955cafb5321253fe93
        d1232c45ed4ce9c9990d7dffdc013051552c63a0b0c76deee4cc36d032409691
        dd436b26aad87cd6167511a65a4a4e861f51533c011a1614c654fb121d66b9db
        4f62620d4bdd460107f9fed10d6b69a2d39f6cc2cc06000000
        """,
    )

    /** The refinement that carries the last two, over signs the pass before it fixed. */
    val UPGRADE_THIRD = hex(
        """
        c0cc0c000000caacccca0001c3cc0a00000000400001c1cc0c00000001000000
        0100c4cc190200004001000103010100ca010000000000004000400066666666
        6600444400000044440000004444000000012222000000222200000022220000
        0002000000000000000000000000000000c7ccca010000000000000000000260
        0030006000300060003000443254ede5320d51dd2e9ddf05f90aa25b429270d4
        6a532fe726becbd6d9baf3baae97965679b921984b0175eed7faaa4f88c88604
        5ed1bb3a8c28e449d3be3ec29c47263bd8ed56d998b8dfa26bb8e6807bf71e14
        66220940ddf1ee73ff5c99038c3e2e32cd22fba2490ddfc9e81e2f0703423994
        e5f6232706d9846b01f9bf235bda2417f245c5c646d718ca6b45510ad5d2f486
        0e422ed74a751265f88c16ec8856511bdc4ac8ee6f4177cb43f3215f8c38d736
        1efa4b1c97fdd2374c4e3ae1f58c524717da32c3359f4ef9b5043eb3942b4b7d
        c930b9007ae0d71fe13cc7d3fdac0dc7f803dab25b361678f4dc30c92fbb34d3
        aa57d99c66e4122ae6a1a39949071ada58ecbc2e4f5c30616c32eec7397a65f8
        9887ef4a93d37513e199e2d17750fa28eb770cd2664d46c6f60e8b7dcf1b3261
        4f4160f4b9c323c0ae2d4f036ad81916c43c75a1e4f82f80c1a3cb7462501e8a
        d0e3a94bdd17b8a9984a3fa48b0f6fbfbb721b265d09ce9c58bfa8267f60fd79
        8ae4ab24d97b3f7ee95cc790d1393b74868db79682bc458ae423182a90ccfb20
        cbe3543499dfdc56d66c1c6b171aa6d83ec819cfdfced25c56ed72c2cc060000
        00
        """,
    )

    /** A tile of dense coefficients, neighbouring sub-bands scaled by different factors. */
    val DENSE = hex(
        """
        c0cc0c000000caacccca0001c3cc0a00000000400001c1cc0c00000001000000
        0100c4cc2a02000040010001000101000b020000000000004000400067676767
        67c5cc0b0200000000000000000000ae00a300a40000009e0002274507ff481f
        b2032604f9e707809511828306320731014f33203e1c04440a1a183220322029
        4e648786fc6102f2058404ec0b480ba3ea817438235825d428329039880c4c13
        63e54350a893032182a2072bfcf06b2fe18399f44262c70cd022123041752a9c
        ae38c32ce9f3142c23a0a47871be8627c24043cd363647135699d21cc5d82165
        1031c3b8a25af05208395e23181a5ca4f3a3d0d91f907126d5118dd335862042
        268e21a800bfd00044fe0a1f240792020c07e55819097430286039031206dcd2
        81186822c4503983a206640ba72e1f8bd7081c481c880c980a20611f483486d0
        98255050d881b903260de3490f22ac260c260a880b3fab0312f260cdf4c9ae71
        9418848bc0b257738c2619609f228a88ee53b4393fca28cd4043ca6a19444cc1
        3150e05cb832ab4614dbcbc982919b9598cc1aca667e13536af90e5cdbcb8d5a
        2819a2156e820880bfe800117f4507640f04055814e5283412a46044c0720704
        05b9e90670d445c850c4c19903420724eca15af3840e0404c818b06440d63e70
        6b0523304b90a0c880a9039305b1a68502ad8c0c4c084807bfed02c5f940c1f4
        24d41c6d044245a041251cd2380644268942846b94f1810fa50c708010eb3706
        6b136c9908722e4c15ad8637dd22d2c0ae5111468c45aac58f651118e76038d3
        50980dde6218823837088120c2cc06000000
        """,
    )

    /** What FreeRDP decodes [PICTURE] to. */
    val PICTURE_SAMPLE = samples(
        """
        ff1f3050 ff1f3050 ff1f304e ff1f3050 ff202f4e ff202f50 ff202f4e ff1f3050
        ff1f3050 ff202f50 ff20304f ffeae7e6 ffe8e8e8 ffeae7e7 ff203050 ff1f3050
        ff1f3050 ff202f4f ff1e2f4f ffe7e8e6 ffe6e8e7 ffe6e8e8 ff1f2f51 ff1f2f50
        ff1f3050 ff1f3050 ff1e304e ff1b2f4f ff1c2f4f ff1d2e50 ff203050 ff1f3050
        ff202f50 ff20304f ff20304f ff1f2f4f ff203051 ff1f3050 ff202f50 ff1f2f4f
        ff00408d ff1f3f91 ff414190 ff5f3d8e ff7e4191 ff9d3f8e ffbf3f8e ffdf408e
        ff003f90 ff1f408f ff403f8f ff5f3f8f ff804090 ff9f4090 ffc0408f ffe04090
        ff003f90 ff21408f ff3f408f ff5f4090 ff7f408f ff9f4090 ffbe408e ffdf3f8f
        """,
    )

    /** What FreeRDP decodes [EXTRAPOLATED] to. */
    val EXTRAPOLATED_SAMPLE = samples(
        """
        ff8a7f83 ff868082 ff808080 ff808080 ff808080 ff808080 ff808080 ff808080
        ff877f82 ff838081 ff969390 ff9a9794 ff9a9794 ff828282 ff808080 ff787f7f
        ff1e2f4f ff364661 ff021639 ff0a1d3f ff00032b ff253453 ff182e4e ff1c3252
        ff011639 ffafb4bb ff9ba2ad ffd1d2d5 ff112144 ff203051 ff082043 ff263553
        fff8f8f5 fffbfcf7 ffffffff ff415068 ff233453 ff1e2e4f ff03163d ff1d2e50
        ff535472 ff5b5877 ff28244d ff352e52 ff1b2541 ff212f49 ff20304b ff21374f
        ff09193d ff0a163d ff212d50 ff002977 ff0d2d7c ff41428e ff70529a ff90569e
        ffa14285 ffb53b7e ff003e8f ff153c92 ff3a3c93 ff654398 ff8e479d ffb1479d
        """,
    )

    /** What FreeRDP decodes [UPGRADE_FIRST] to. */
    val UPGRADE_FIRST_SAMPLE = samples(
        """
        ff7d7f7f ff808080 ff808080 ff808080 ff808080 ff808080 ff808080 ff808080
        ff808080 ff808080 ff858484 ff868585 ff868585 ff808080 ff808080 ff7e7f7f
        ff808080 ff8a8988 ff8d8d8a ff8e8e8b ff838382 ff808080 ff7f7f7f ff808080
        ff7a7b7b ff787879 ff777879 ff878685 ff808080 ff808080 ff767778 ff808080
        ff717375 ff717375 ff7c7c7d ff8c8e7a ff808271 ff808080 ff737185 ff838091
        ff858183 ff868283 ff858082 ff897d9f ff807a9c ff808080 ff7d8261 ff808563
        ff898184 ff8c8185 ff858292 ff7d76bc ff7f78af ff828084 ff848083 ff868083
        ff7f7f7f ff7f7f7f ff808080 ff808080 ff808080 ff808080 ff808080 ff808080
        """,
    )

    /** The tile after [UPGRADE_SECOND] lands on it. */
    val UPGRADE_SECOND_SAMPLE = samples(
        """
        ff94887c ff7e8265 ff927c84 ff9d8182 ffa28895 ff888290 ffa0738e ff8d6999
        ff887795 ff898d80 ffa883a9 ff998599 ffa1878d ff9d8ea1 ff8c788d ff9a818f
        ff7a728d ff9a7e9b ffa88fb4 ff9a8d9a ffa17985 ff9b7888 ff877d89 ff958f85
        ff8375a3 ff81757c ff947993 ff928e86 ff927c97 ff7b7597 ff7c7b87 ff998181
        ff796b9b ff787792 ff908187 ffb08f8d ff917963 ff938590 ffa273a8 ff8f7ca8
        ff8b8e9d ff8c8896 ff8e7d8b ff9675b8 ff7e75a8 ff808690 ff878579 ff748751
        ff98889e ffb38293 ff9c7fac ffa96adb ff9173bd ff897f9d ff8b7892 ff85727f
        ff868283 ffaa8491 ff9784a6 ff978ba3 ff8b8094 ff8a8397 ff798698 ff917192
        """,
    )

    /** The tile after [UPGRADE_THIRD] lands on it. */
    val UPGRADE_THIRD_SAMPLE = samples(
        """
        ff9a8d77 ff7f8665 ff977c83 ffa3827f ffa88a9c ff928499 ffa67492 ff9263a1
        ff857897 ff878f87 ffab83ad ff9f869f ffa78897 ffa38fa6 ff95788f ffa18294
        ff7c6d8b ff9c7ca2 ffae8ebd ffa489a6 ffa87482 ff9f7c8b ff8e7a8d ff959288
        ff8475ab ff80727b ff987694 ff958c8b ff907a9c ff7e779b ff827a8f ff9c8383
        ff826ba6 ff7d7692 ff958287 ffb6908f ff9b7566 ff978a8e ffa877ac ff977fb5
        ff928ca8 ff908899 ff917c8c ff9975c0 ff8972ae ff838a92 ff898d7b ff798e55
        ff9d89a1 ffbd839b ff9b7cb0 ffae67e1 ff9a6ec8 ff8d809f ff8c7999 ff857283
        ff907f85 ffb7869a ff9982b0 ff9f8ba9 ff90819a ff8b80a0 ff798b9d ff917395
        """,
    )

    /** What FreeRDP decodes [DENSE] to. */
    val DENSE_SAMPLE = samples(
        """
        ff93c400 ffabf994 ff8eba8b ff8eba8b ff8eba8b ff8eba8b ff8eba8b ff8eba8b
        ff8ab464 ff91d47f ff8eba8b ff9abe84 ff8eba8b ff8eba8b ff8eba8b ff3c91a2
        ff519da2 ff0397c1 ff006785 ff006082 ff006082 ff006082 ff004560 ff006097
        ff006282 ff00544b ff006d7e ff005977 ff005d71 ff006082 ff004560 ff006eab
        ff00513b ff00584f ff00686d ff00596c ff003425 ff1b727e ff157e76 ff157e76
        ff148789 ff0d7975 ff157e76 ff157e76 ff0bb3ce ff0bb3ce ff0bb3ce ff0bb3ce
        ff0bb3ce ff007792 ff12b1d7 ff0096bd ff0bb3ce ff00e5b0 ff00e0a8 ff00cd76
        ff00e5b0 ff00e4b9 ff00e4b9 ff00c89f ff00e2b9 ff00e5b0 ff00e5b0 ff00d4a7
        """,
    )

    /** The whole tile behind PICTURE_SAMPLE, folded so that a pixel the sample skips still counts. */
    const val PICTURE_FINGERPRINT = -1927380932

    /** The whole tile behind EXTRAPOLATED_SAMPLE, folded so that a pixel the sample skips still counts. */
    const val EXTRAPOLATED_FINGERPRINT = 2976613

    /** The whole tile behind UPGRADE_FIRST_SAMPLE, folded so that a pixel the sample skips still counts. */
    const val UPGRADE_FIRST_FINGERPRINT = 1960101169

    /** The whole tile behind UPGRADE_SECOND_SAMPLE, folded so that a pixel the sample skips still counts. */
    const val UPGRADE_SECOND_FINGERPRINT = 2022454928

    /** The whole tile behind UPGRADE_THIRD_SAMPLE, folded so that a pixel the sample skips still counts. */
    const val UPGRADE_THIRD_FINGERPRINT = 1221537535

    /** The whole tile behind DENSE_SAMPLE, folded so that a pixel the sample skips still counts. */
    const val DENSE_FINGERPRINT = 1712816621
}
