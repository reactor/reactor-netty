package reactor.netty.util;

import java.util.Map;
import java.util.function.Function;

/**
 * A temporary workaround for Java 8 specific performance issue JDK-8161372.
 * This class should be removed once the Java 8 support is dropped.
 *
 * @author zimatars
 */
public class MapUtil {

	private MapUtil() {
	}

	/**
	 * ConcurrentHashMap.computeIfAbsent(k,v) locks bin when k present.
	 * Add pre-screen before locking inside computeIfAbsent.
	 *
	 * @param map the ConcurrentHashMap instance
	 * @param key key with which the specified value is to be associated
	 * @param mappingFunction the function to compute a value
	 * @return the current (existing or computed) value associated with
	 *         the specified key, or null if the computed value is null
	 */
	public static <K, V> V computeIfAbsent(Map<K, V> map, K key, Function<K, V> mappingFunction) {
		V value = map.get(key);
		return value != null ? value : map.computeIfAbsent(key, mappingFunction);
	}

}
