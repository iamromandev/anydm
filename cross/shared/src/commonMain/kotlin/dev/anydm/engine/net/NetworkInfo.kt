package dev.anydm.engine.net

/**
 * One network the machine can reach the internet by (BND-01): a Wi-Fi or Ethernet link, a tethered phone. The [key] is the
 * name of its interface (`en0`, `eth1`), the same string [NetworkIdentity] gives for the default route, so the bytes a
 * bonded connection carries are counted under the key of the network they left by.
 */
data class NetworkInfo(
    val key: String,
    /** What a person calls it; the interface name until a platform knows better. */
    val name: String,
    val up: Boolean,
    /** Its local addresses, as text; the ones a connection on it can use as a source. */
    val addresses: List<String>,
    /** The OS's number for the interface; 0 when it has none. `IP_BOUND_IF` and `IP_UNICAST_IF` take it. */
    val index: Int = 0,
)

/** Whether a connection can really be kept on one network (BND-02). */
enum class BindStatus {
    /** Not looked at yet. */
    UNKNOWN,

    /** The OS keeps the traffic on that interface. */
    ISOLATED,

    /** Only the source address is set: without routing rules for it the traffic may still leave by the default route (Linux). */
    BEST_EFFORT,

    /** Nothing can be bound to it here; the network is listed but never used. */
    UNSUPPORTED,
}
