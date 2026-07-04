

@file:Suppress("RemoveRedundantBackticks")

package dev.soranerai.vpnhidenext.checks

// Common helper code.
//
// Ideally this would live in a separate .kt file where it can be unittested etc
// in isolation, and perhaps even published as a re-useable package.
//
// However, it's important that the details of how this helper code works (e.g. the
// way that different builtin types are passed across the FFI) exactly match what's
// expected by the Rust code on the other side of the interface. In practice right
// now that means coming from the exact some version of `uniffi` that was used to
// compile the Rust component. The easiest way to ensure this is to bundle the Kotlin
// helpers directly inline like we're doing here.

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure


internal typealias Pointer = com.sun.jna.Pointer
internal val NullPointer: Pointer? = com.sun.jna.Pointer.NULL
internal fun Pointer.toLong(): Long = Pointer.nativeValue(this)
internal fun kotlin.Long.toPointer() = com.sun.jna.Pointer(this)


@kotlin.jvm.JvmInline
public value class ByteBuffer(private val inner: java.nio.ByteBuffer) {
    init {
        inner.order(java.nio.ByteOrder.BIG_ENDIAN)
    }

    public fun internal(): java.nio.ByteBuffer = inner

    public fun limit(): Int = inner.limit()

    public fun position(): Int = inner.position()

    public fun hasRemaining(): Boolean = inner.hasRemaining()

    public fun get(): Byte = inner.get()

    public fun get(bytesToRead: Int): ByteArray = ByteArray(bytesToRead).apply(inner::get)

    public fun getShort(): Short = inner.getShort()

    public fun getInt(): Int = inner.getInt()

    public fun getLong(): Long = inner.getLong()

    public fun getFloat(): Float = inner.getFloat()

    public fun getDouble(): Double = inner.getDouble()

    public fun put(value: Byte) {
        inner.put(value)
    }

    public fun put(src: ByteArray) {
        inner.put(src)
    }

    public fun putShort(value: Short) {
        inner.putShort(value)
    }

    public fun putInt(value: Int) {
        inner.putInt(value)
    }

    public fun putLong(value: Long) {
        inner.putLong(value)
    }

    public fun putFloat(value: Float) {
        inner.putFloat(value)
    }

    public fun putDouble(value: Double) {
        inner.putDouble(value)
    }
}
public fun RustBuffer.setValue(array: RustBufferByValue) {
    this.data = array.data
    this.len = array.len
    this.capacity = array.capacity
}

internal object RustBufferHelper {
    internal fun allocValue(size: ULong = 0UL): RustBufferByValue = uniffiRustCall { status ->
        // Note: need to convert the size to a `Long` value to make this work with JVM.
        UniffiLib.ffi_vpnhide_checks_rustbuffer_alloc(size.toLong(), status)
    }.also {
        if(it.data == null) {
            throw RuntimeException("RustBuffer.alloc() returned null data pointer (size=${size})")
        }
    }

    internal fun free(buf: RustBufferByValue) = uniffiRustCall { status ->
        UniffiLib.ffi_vpnhide_checks_rustbuffer_free(buf, status)
    }
}

@Structure.FieldOrder("capacity", "len", "data")
public open class RustBufferStruct(
    // Note: `capacity` and `len` are actually `ULong` values, but JVM only supports signed values.
    // When dealing with these fields, make sure to call `toULong()`.
    @JvmField public var capacity: Long,
    @JvmField public var len: Long,
    @JvmField public var data: Pointer?,
) : Structure() {
    public constructor(): this(0.toLong(), 0.toLong(), null)

    public class ByValue(
        capacity: Long,
        len: Long,
        data: Pointer?,
    ): RustBuffer(capacity, len, data), Structure.ByValue {
        public constructor(): this(0.toLong(), 0.toLong(), null)
    }

    /**
     * The equivalent of the `*mut RustBuffer` type.
     * Required for callbacks taking in an out pointer.
     *
     * Size is the sum of all values in the struct.
     */
    public class ByReference(
        capacity: Long,
        len: Long,
        data: Pointer?,
    ): RustBuffer(capacity, len, data), Structure.ByReference {
        public constructor(): this(0.toLong(), 0.toLong(), null)
    }
}

public typealias RustBuffer = RustBufferStruct
public typealias RustBufferByValue = RustBufferStruct.ByValue

internal fun RustBuffer.asByteBuffer(): ByteBuffer? {
    require(this.len <= Int.MAX_VALUE) {
        val length = this.len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
    return ByteBuffer(data?.getByteBuffer(0L, this.len) ?: return null)
}

internal fun RustBufferByValue.asByteBuffer(): ByteBuffer? {
    require(this.len <= Int.MAX_VALUE) {
        val length = this.len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
    return ByteBuffer(data?.getByteBuffer(0L, this.len) ?: return null)
}

// This is a helper for safely passing byte references into the rust code.
// It's not actually used at the moment, because there aren't many things that you
// can take a direct pointer to in the JVM, and if we're going to copy something
// then we might as well copy it into a `RustBuffer`. But it's here for API
// completeness.

@Structure.FieldOrder("len", "data")
internal open class ForeignBytesStruct : Structure() {
    @JvmField var len: Int = 0
    @JvmField var data: Pointer? = null

    internal class ByValue : ForeignBytes(), Structure.ByValue
}
internal typealias ForeignBytes = ForeignBytesStruct
internal typealias ForeignBytesByValue = ForeignBytesStruct.ByValue

public interface FfiConverter<KotlinType, FfiType> {
    // Convert an FFI type to a Kotlin type
    public fun lift(value: FfiType): KotlinType

    // Convert an Kotlin type to an FFI type
    public fun lower(value: KotlinType): FfiType

    // Read a Kotlin type from a `ByteBuffer`
    public fun read(buf: ByteBuffer): KotlinType

    // Calculate bytes to allocate when creating a `RustBuffer`
    //
    // This must return at least as many bytes as the write() function will
    // write. It can return more bytes than needed, for example when writing
    // Strings we can't know the exact bytes needed until we the UTF-8
    // encoding, so we pessimistically allocate the largest size possible (3
    // bytes per codepoint).  Allocating extra bytes is not really a big deal
    // because the `RustBuffer` is short-lived.
    public fun allocationSize(value: KotlinType): ULong

    // Write a Kotlin type to a `ByteBuffer`
    public fun write(value: KotlinType, buf: ByteBuffer)

    // Lower a value into a `RustBuffer`
    //
    // This method lowers a value into a `RustBuffer` rather than the normal
    // FfiType.  It's used by the callback interface code.  Callback interface
    // returns are always serialized into a `RustBuffer` regardless of their
    // normal FFI type.
    public fun lowerIntoRustBuffer(value: KotlinType): RustBufferByValue {
        val rbuf = RustBufferHelper.allocValue(allocationSize(value))
        val bbuf = rbuf.asByteBuffer()!!
        write(value, bbuf)
        return RustBufferByValue(
            capacity = rbuf.capacity,
            len = bbuf.position().toLong(),
            data = rbuf.data,
        )
    }

    // Lift a value from a `RustBuffer`.
    //
    // This here mostly because of the symmetry with `lowerIntoRustBuffer()`.
    // It's currently only used by the `FfiConverterRustBuffer` class below.
    public fun liftFromRustBuffer(rbuf: RustBufferByValue): KotlinType {
        val byteBuf = rbuf.asByteBuffer()!!
        try {
           val item = read(byteBuf)
           if (byteBuf.hasRemaining()) {
               throw RuntimeException("junk remaining in buffer after lifting, something is very wrong!!")
           }
           return item
        } finally {
            RustBufferHelper.free(rbuf)
        }
    }
}

// FfiConverter that uses `RustBuffer` as the FfiType
public interface FfiConverterRustBuffer<KotlinType>: FfiConverter<KotlinType, RustBufferByValue> {
    override fun lift(value: RustBufferByValue): KotlinType = liftFromRustBuffer(value)
    override fun lower(value: KotlinType): RustBufferByValue = lowerIntoRustBuffer(value)
}

internal const val UNIFFI_CALL_SUCCESS = 0.toByte()
internal const val UNIFFI_CALL_ERROR = 1.toByte()
internal const val UNIFFI_CALL_UNEXPECTED_ERROR = 2.toByte()

// Default Implementations
internal fun UniffiRustCallStatus.isSuccess(): Boolean
    = code == UNIFFI_CALL_SUCCESS

internal fun UniffiRustCallStatus.isError(): Boolean
    = code == UNIFFI_CALL_ERROR

internal fun UniffiRustCallStatus.isPanic(): Boolean
    = code == UNIFFI_CALL_UNEXPECTED_ERROR

internal fun UniffiRustCallStatusByValue.isSuccess(): Boolean
    = code == UNIFFI_CALL_SUCCESS

internal fun UniffiRustCallStatusByValue.isError(): Boolean
    = code == UNIFFI_CALL_ERROR

internal fun UniffiRustCallStatusByValue.isPanic(): Boolean
    = code == UNIFFI_CALL_UNEXPECTED_ERROR

// Each top-level error class has a companion object that can lift the error from the call status's rust buffer
public interface UniffiRustCallStatusErrorHandler<E> {
    public fun lift(errorBuf: RustBufferByValue): E
}

// Helpers for calling Rust
// In practice we usually need to be synchronized to call this safely, so it doesn't
// synchronize itself

// Call a rust function that returns a Result<>.  Pass in the Error class companion that corresponds to the Err
internal inline fun <U, E: kotlin.Exception> uniffiRustCallWithError(errorHandler: UniffiRustCallStatusErrorHandler<E>, crossinline callback: (UniffiRustCallStatus) -> U): U {
    return UniffiRustCallStatusHelper.withReference() { status ->
        val returnValue = callback(status)
        uniffiCheckCallStatus(errorHandler, status)
        returnValue
    }
}

// Check `status` and throw an error if the call wasn't successful
internal fun<E: kotlin.Exception> uniffiCheckCallStatus(errorHandler: UniffiRustCallStatusErrorHandler<E>, status: UniffiRustCallStatus) {
    if (status.isSuccess()) {
        return
    } else if (status.isError()) {
        throw errorHandler.lift(status.errorBuf)
    } else if (status.isPanic()) {
        // when the rust code sees a panic, it tries to construct a rustbuffer
        // with the message.  but if that code panics, then it just sends back
        // an empty buffer.
        if (status.errorBuf.len > 0) {
            throw InternalException(FfiConverterString.lift(status.errorBuf))
        } else {
            throw InternalException("Rust panic")
        }
    } else {
        throw InternalException("Unknown rust call status: $status.code")
    }
}

// UniffiRustCallStatusErrorHandler implementation for times when we don't expect a CALL_ERROR
public object UniffiNullRustCallStatusErrorHandler: UniffiRustCallStatusErrorHandler<InternalException> {
    override fun lift(errorBuf: RustBufferByValue): InternalException {
        RustBufferHelper.free(errorBuf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

// Call a rust function that returns a plain value
internal inline fun <U> uniffiRustCall(crossinline callback: (UniffiRustCallStatus) -> U): U {
    return uniffiRustCallWithError(UniffiNullRustCallStatusErrorHandler, callback)
}

internal inline fun<T> uniffiTraitInterfaceCall(
    callStatus: UniffiRustCallStatus,
    makeCall: () -> T,
    writeReturn: (T) -> Unit,
) {
    try {
        writeReturn(makeCall())
    } catch(e: kotlin.Exception) {
        callStatus.code = UNIFFI_CALL_UNEXPECTED_ERROR
        callStatus.errorBuf = FfiConverterString.lower(e.toString())
    }
}

internal inline fun<T, reified E: Throwable> uniffiTraitInterfaceCallWithError(
    callStatus: UniffiRustCallStatus,
    makeCall: () -> T,
    writeReturn: (T) -> Unit,
    lowerError: (E) -> RustBufferByValue
) {
    try {
        writeReturn(makeCall())
    } catch(e: kotlin.Exception) {
        if (e is E) {
            callStatus.code = UNIFFI_CALL_ERROR
            callStatus.errorBuf = lowerError(e)
        } else {
            callStatus.code = UNIFFI_CALL_UNEXPECTED_ERROR
            callStatus.errorBuf = FfiConverterString.lower(e.toString())
        }
    }
}

@Structure.FieldOrder("code", "errorBuf")
internal open class UniffiRustCallStatusStruct(
    @JvmField public var code: Byte,
    @JvmField public var errorBuf: RustBufferByValue,
) : Structure() {
    internal constructor(): this(0.toByte(), RustBufferByValue())

    internal class ByValue(
        code: Byte,
        errorBuf: RustBufferByValue,
    ): UniffiRustCallStatusStruct(code, errorBuf), Structure.ByValue {
        internal constructor(): this(0.toByte(), RustBufferByValue())
    }
    internal class ByReference(
        code: Byte,
        errorBuf: RustBufferByValue,
    ): UniffiRustCallStatusStruct(code, errorBuf), Structure.ByReference {
        internal constructor(): this(0.toByte(), RustBufferByValue())
    }
}

internal typealias UniffiRustCallStatus = UniffiRustCallStatusStruct.ByReference
internal typealias UniffiRustCallStatusByValue = UniffiRustCallStatusStruct.ByValue

internal object UniffiRustCallStatusHelper {
    internal fun allocValue() = UniffiRustCallStatusByValue()
    internal fun <U> withReference(block: (UniffiRustCallStatus) -> U): U {
        val status = UniffiRustCallStatus()
        return block(status)
    }
}

internal class UniffiHandleMap<T: Any> {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, T>()
    private val counter: kotlinx.atomicfu.AtomicLong = kotlinx.atomicfu.atomic(1L)

    internal val size: Int
        get() = map.size

    // Insert a new object into the handle map and get a handle for it
    internal fun insert(obj: T): Long {
        val handle = counter.getAndAdd(1)
        map[handle] = obj
        return handle
    }

    // Get an object from the handle map
    internal fun get(handle: Long): T {
        return map[handle] ?: throw InternalException("UniffiHandleMap.get: Invalid handle")
    }

    // Remove an entry from the handlemap and get the Kotlin object back
    internal fun remove(handle: Long): T {
        return map.remove(handle) ?: throw InternalException("UniffiHandleMap.remove: Invalid handle")
    }
}

internal typealias ByteByReference = com.sun.jna.ptr.ByteByReference
internal typealias DoubleByReference = com.sun.jna.ptr.DoubleByReference
internal typealias FloatByReference = com.sun.jna.ptr.FloatByReference
internal typealias IntByReference = com.sun.jna.ptr.IntByReference
internal typealias LongByReference = com.sun.jna.ptr.LongByReference
internal typealias PointerByReference = com.sun.jna.ptr.PointerByReference
internal typealias ShortByReference = com.sun.jna.ptr.ShortByReference

// Contains loading, initialization code,
// and the FFI Function declarations in a com.sun.jna.Library.

// Define FFI callback types
internal interface UniffiRustFutureContinuationCallback: com.sun.jna.Callback {
    public fun callback(`data`: Long,`pollResult`: Byte,)
}
internal interface UniffiForeignFutureFree: com.sun.jna.Callback {
    public fun callback(`handle`: Long,)
}
internal interface UniffiCallbackInterfaceFree: com.sun.jna.Callback {
    public fun callback(`handle`: Long,)
}
@Structure.FieldOrder("handle", "free")
internal open class UniffiForeignFutureStruct(
    @JvmField public var `handle`: Long,
    @JvmField public var `free`: UniffiForeignFutureFree?,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `handle` = 0.toLong(),
        
        `free` = null,
        
    )

    internal class UniffiByValue(
        `handle`: Long,
        `free`: UniffiForeignFutureFree?,
    ): UniffiForeignFuture(`handle`,`free`,), Structure.ByValue
}

internal typealias UniffiForeignFuture = UniffiForeignFutureStruct

internal fun UniffiForeignFuture.uniffiSetValue(other: UniffiForeignFuture) {
    `handle` = other.`handle`
    `free` = other.`free`
}
internal fun UniffiForeignFuture.uniffiSetValue(other: UniffiForeignFutureUniffiByValue) {
    `handle` = other.`handle`
    `free` = other.`free`
}

internal typealias UniffiForeignFutureUniffiByValue = UniffiForeignFutureStruct.UniffiByValue
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU8Struct(
    @JvmField public var `returnValue`: Byte,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toByte(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Byte,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU8(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU8 = UniffiForeignFutureStructU8Struct

internal fun UniffiForeignFutureStructU8.uniffiSetValue(other: UniffiForeignFutureStructU8) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU8.uniffiSetValue(other: UniffiForeignFutureStructU8UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU8UniffiByValue = UniffiForeignFutureStructU8Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU8: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU8UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI8Struct(
    @JvmField public var `returnValue`: Byte,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toByte(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Byte,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI8(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI8 = UniffiForeignFutureStructI8Struct

internal fun UniffiForeignFutureStructI8.uniffiSetValue(other: UniffiForeignFutureStructI8) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI8.uniffiSetValue(other: UniffiForeignFutureStructI8UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI8UniffiByValue = UniffiForeignFutureStructI8Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI8: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI8UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU16Struct(
    @JvmField public var `returnValue`: Short,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toShort(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Short,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU16(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU16 = UniffiForeignFutureStructU16Struct

internal fun UniffiForeignFutureStructU16.uniffiSetValue(other: UniffiForeignFutureStructU16) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU16.uniffiSetValue(other: UniffiForeignFutureStructU16UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU16UniffiByValue = UniffiForeignFutureStructU16Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU16: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU16UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI16Struct(
    @JvmField public var `returnValue`: Short,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toShort(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Short,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI16(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI16 = UniffiForeignFutureStructI16Struct

internal fun UniffiForeignFutureStructI16.uniffiSetValue(other: UniffiForeignFutureStructI16) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI16.uniffiSetValue(other: UniffiForeignFutureStructI16UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI16UniffiByValue = UniffiForeignFutureStructI16Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI16: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI16UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU32Struct(
    @JvmField public var `returnValue`: Int,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Int,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU32(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU32 = UniffiForeignFutureStructU32Struct

internal fun UniffiForeignFutureStructU32.uniffiSetValue(other: UniffiForeignFutureStructU32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU32.uniffiSetValue(other: UniffiForeignFutureStructU32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU32UniffiByValue = UniffiForeignFutureStructU32Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU32: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU32UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI32Struct(
    @JvmField public var `returnValue`: Int,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Int,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI32(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI32 = UniffiForeignFutureStructI32Struct

internal fun UniffiForeignFutureStructI32.uniffiSetValue(other: UniffiForeignFutureStructI32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI32.uniffiSetValue(other: UniffiForeignFutureStructI32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI32UniffiByValue = UniffiForeignFutureStructI32Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI32: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI32UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU64Struct(
    @JvmField public var `returnValue`: Long,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toLong(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Long,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU64(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU64 = UniffiForeignFutureStructU64Struct

internal fun UniffiForeignFutureStructU64.uniffiSetValue(other: UniffiForeignFutureStructU64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU64.uniffiSetValue(other: UniffiForeignFutureStructU64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU64UniffiByValue = UniffiForeignFutureStructU64Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU64: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU64UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI64Struct(
    @JvmField public var `returnValue`: Long,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toLong(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Long,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI64(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI64 = UniffiForeignFutureStructI64Struct

internal fun UniffiForeignFutureStructI64.uniffiSetValue(other: UniffiForeignFutureStructI64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI64.uniffiSetValue(other: UniffiForeignFutureStructI64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI64UniffiByValue = UniffiForeignFutureStructI64Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI64: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI64UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructF32Struct(
    @JvmField public var `returnValue`: Float,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.0f,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Float,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructF32(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructF32 = UniffiForeignFutureStructF32Struct

internal fun UniffiForeignFutureStructF32.uniffiSetValue(other: UniffiForeignFutureStructF32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructF32.uniffiSetValue(other: UniffiForeignFutureStructF32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructF32UniffiByValue = UniffiForeignFutureStructF32Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteF32: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructF32UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructF64Struct(
    @JvmField public var `returnValue`: Double,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.0,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Double,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructF64(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructF64 = UniffiForeignFutureStructF64Struct

internal fun UniffiForeignFutureStructF64.uniffiSetValue(other: UniffiForeignFutureStructF64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructF64.uniffiSetValue(other: UniffiForeignFutureStructF64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructF64UniffiByValue = UniffiForeignFutureStructF64Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteF64: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructF64UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructPointerStruct(
    @JvmField public var `returnValue`: Pointer?,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = NullPointer,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Pointer?,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructPointer(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructPointer = UniffiForeignFutureStructPointerStruct

internal fun UniffiForeignFutureStructPointer.uniffiSetValue(other: UniffiForeignFutureStructPointer) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructPointer.uniffiSetValue(other: UniffiForeignFutureStructPointerUniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructPointerUniffiByValue = UniffiForeignFutureStructPointerStruct.UniffiByValue
internal interface UniffiForeignFutureCompletePointer: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructPointerUniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructRustBufferStruct(
    @JvmField public var `returnValue`: RustBufferByValue,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = RustBufferHelper.allocValue(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: RustBufferByValue,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructRustBuffer(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructRustBuffer = UniffiForeignFutureStructRustBufferStruct

internal fun UniffiForeignFutureStructRustBuffer.uniffiSetValue(other: UniffiForeignFutureStructRustBuffer) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructRustBuffer.uniffiSetValue(other: UniffiForeignFutureStructRustBufferUniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructRustBufferUniffiByValue = UniffiForeignFutureStructRustBufferStruct.UniffiByValue
internal interface UniffiForeignFutureCompleteRustBuffer: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructRustBufferUniffiByValue,)
}
@Structure.FieldOrder("callStatus")
internal open class UniffiForeignFutureStructVoidStruct(
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructVoid(`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructVoid = UniffiForeignFutureStructVoidStruct

internal fun UniffiForeignFutureStructVoid.uniffiSetValue(other: UniffiForeignFutureStructVoid) {
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructVoid.uniffiSetValue(other: UniffiForeignFutureStructVoidUniffiByValue) {
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructVoidUniffiByValue = UniffiForeignFutureStructVoidStruct.UniffiByValue
internal interface UniffiForeignFutureCompleteVoid: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructVoidUniffiByValue,)
}
















































































































































@Synchronized
private fun findLibraryName(componentName: String): String {
    val libOverride = System.getProperty("uniffi.component.$componentName.libraryOverride")
    if (libOverride != null) {
        return libOverride
    }
    return "vpnhide_checks"
}

// For large crates we prevent `MethodTooLargeException` (see #2340)
// N.B. the name of the extension is very misleading, since it is 
// rather `InterfaceTooLargeException`, caused by too many methods 
// in the interface for large crates.
//
// By splitting the otherwise huge interface into two parts
// * UniffiLib 
// * IntegrityCheckingUniffiLib (this)
// we allow for ~2x as many methods in the UniffiLib interface.
// 
// The `ffi_uniffi_contract_version` method and all checksum methods are put 
// into `IntegrityCheckingUniffiLib` and these methods are called only once,
// when the library is loaded.
internal object IntegrityCheckingUniffiLib : Library {
    init {
        Native.register(IntegrityCheckingUniffiLib::class.java, findLibraryName("vpnhide_checks"))
        uniffiCheckContractApiVersion()
        uniffiCheckApiChecksums()
    }

    private fun uniffiCheckContractApiVersion() {
        // Get the bindings contract version from our ComponentInterface
        val bindingsContractVersion = 29
        // Get the scaffolding contract version by calling the into the dylib
        val scaffoldingContractVersion = ffi_vpnhide_checks_uniffi_contract_version()
        if (bindingsContractVersion != scaffoldingContractVersion) {
            throw RuntimeException("UniFFI contract version mismatch: try cleaning and rebuilding your project")
        }
    }
    private fun uniffiCheckApiChecksums() {
        if (uniffi_vpnhide_checks_checksum_func_check_arm_timing() != 31980.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_bpf_iface_map() != 22365.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_direct_syscall() != 59611.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_getifaddrs() != 54935.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_getsockname_spoof() != 59588.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_getsockopt_bind() != 63740.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_gso_asymmetry() != 575.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_inet_diag() != 60175.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_ioctl_alternative() != 32695.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_ioctl_siocgifconf() != 64280.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_ioctl_siocgifflags() != 20545.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_ioctl_siocgifmtu() != 27723.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_ipv6_link_local_bruteforce() != 57576.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_loopback_bind_conflict() != 927.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_netlink_anonymous_route() != 35373.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_netlink_getlink() != 32545.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_netlink_getneigh() != 11906.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_netlink_getroute() != 44910.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_netlink_getrule() != 43938.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_pmtu_cache_poisoning() != 23199.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_dev() != 61579.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_fib_trie() != 42472.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_if_inet6() != 40572.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_ipv6_route() != 24231.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_route() != 65020.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_tcp() != 41408.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_tcp6() != 51442.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_udp() != 28902.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_net_udp6() != 28210.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_proc_sys_net_conf() != 19536.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_qdisc_by_ifindex() != 3470.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_rtm_getlink_trim_oracle() != 54945.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_sys_class_net() != 40071.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_system_properties() != 16246.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_tcp_info_mss() != 62683.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_tcp_mss() != 32742.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_timestamping_hw() != 63346.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_traceroute_rtt() != 7261.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_udp_pmtu() != 3773.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_udp_queue_pressure() != 38328.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_uid_route_rules_leak() != 60445.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_check_underlay_port_conflict() != 33566.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_vpnhide_checks_checksum_func_parse_proc_net_dev_csv() != 42485.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
    }

    // Integrity check functions only
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_arm_timing(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_bpf_iface_map(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_direct_syscall(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_getifaddrs(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_getsockname_spoof(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_getsockopt_bind(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_gso_asymmetry(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_inet_diag(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_ioctl_alternative(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_ioctl_siocgifconf(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_ioctl_siocgifflags(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_ioctl_siocgifmtu(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_ipv6_link_local_bruteforce(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_loopback_bind_conflict(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_netlink_anonymous_route(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_netlink_getlink(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_netlink_getneigh(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_netlink_getroute(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_netlink_getrule(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_pmtu_cache_poisoning(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_dev(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_fib_trie(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_if_inet6(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_ipv6_route(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_route(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_tcp(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_tcp6(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_udp(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_net_udp6(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_proc_sys_net_conf(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_qdisc_by_ifindex(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_rtm_getlink_trim_oracle(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_sys_class_net(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_system_properties(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_tcp_info_mss(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_tcp_mss(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_timestamping_hw(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_traceroute_rtt(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_udp_pmtu(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_udp_queue_pressure(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_uid_route_rules_leak(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_check_underlay_port_conflict(
    ): Short
    @JvmStatic
    external fun uniffi_vpnhide_checks_checksum_func_parse_proc_net_dev_csv(
    ): Short
    @JvmStatic
    external fun ffi_vpnhide_checks_uniffi_contract_version(
    ): Int
}

// A JNA Library to expose the extern-C FFI definitions.
// This is an implementation detail which will be called internally by the public API.
internal object UniffiLib : Library {

    init {
        IntegrityCheckingUniffiLib
        Native.register(UniffiLib::class.java, findLibraryName("vpnhide_checks"))
        // No need to check the contract version and checksums, since 
        // we already did that with `IntegrityCheckingUniffiLib` above.
    }
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_arm_timing(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_bpf_iface_map(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_direct_syscall(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_getifaddrs(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_getsockname_spoof(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_getsockopt_bind(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_gso_asymmetry(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_inet_diag(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_ioctl_alternative(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_ioctl_siocgifconf(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_ioctl_siocgifflags(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_ioctl_siocgifmtu(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_ipv6_link_local_bruteforce(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_loopback_bind_conflict(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_netlink_anonymous_route(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_netlink_getlink(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_netlink_getneigh(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_netlink_getroute(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_netlink_getrule(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_pmtu_cache_poisoning(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_dev(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_fib_trie(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_if_inet6(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_ipv6_route(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_route(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_tcp(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_tcp6(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_udp(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_net_udp6(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_proc_sys_net_conf(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_qdisc_by_ifindex(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_rtm_getlink_trim_oracle(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_sys_class_net(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_system_properties(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_tcp_info_mss(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_tcp_mss(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_timestamping_hw(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_traceroute_rtt(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_udp_pmtu(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_udp_queue_pressure(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_uid_route_rules_leak(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_check_underlay_port_conflict(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_vpnhide_checks_fn_func_parse_proc_net_dev_csv(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_vpnhide_checks_rustbuffer_alloc(
        `size`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_vpnhide_checks_rustbuffer_from_bytes(
        `bytes`: ForeignBytesByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_vpnhide_checks_rustbuffer_free(
        `buf`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rustbuffer_reserve(
        `buf`: RustBufferByValue,
        `additional`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_u8(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_u8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_u8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_u8(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Byte
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_i8(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_i8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_i8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_i8(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Byte
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_u16(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_u16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_u16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_u16(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Short
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_i16(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_i16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_i16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_i16(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Short
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_u32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_u32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_u32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_u32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Int
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_i32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_i32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_i32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_i32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Int
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_u64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_u64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_u64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_u64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_i64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_i64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_i64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_i64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_f32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_f32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_f32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_f32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Float
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_f64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_f64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_f64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_f64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Double
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_pointer(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_pointer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_pointer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_pointer(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer?
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_rust_buffer(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_rust_buffer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_rust_buffer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_rust_buffer(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_poll_void(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_cancel_void(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_free_void(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_vpnhide_checks_rust_future_complete_void(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
}

public fun uniffiEnsureInitialized() {
    UniffiLib
}

// Public interface members begin here.



public object FfiConverterString: FfiConverter<String, RustBufferByValue> {
    // Note: we don't inherit from FfiConverterRustBuffer, because we use a
    // special encoding when lowering/lifting.  We can use `RustBuffer.len` to
    // store our length and avoid writing it out to the buffer.
    override fun lift(value: RustBufferByValue): String {
        try {
            require(value.len <= Int.MAX_VALUE) {
        val length = value.len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
            val byteArr =  value.asByteBuffer()!!.get(value.len.toInt())
            return byteArr.decodeToString()
        } finally {
            RustBufferHelper.free(value)
        }
    }

    override fun read(buf: ByteBuffer): String {
        val len = buf.getInt()
        val byteArr = buf.get(len)
        return byteArr.decodeToString()
    }

    override fun lower(value: String): RustBufferByValue {
        // TODO: prevent allocating a new byte array here
        val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
        return RustBufferHelper.allocValue(encoded.size.toULong()).apply {
            asByteBuffer()!!.put(encoded)
        }
    }

    // We aren't sure exactly how many bytes our string will be once it's UTF-8
    // encoded.  Allocate 3 bytes per UTF-16 code unit which will always be
    // enough.
    override fun allocationSize(value: String): ULong {
        val sizeForLength = 4UL
        val sizeForString = value.length.toULong() * 3UL
        return sizeForLength + sizeForString
    }

    override fun write(value: String, buf: ByteBuffer) {
        // TODO: prevent allocating a new byte array here
        val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
        buf.putInt(encoded.size)
        buf.put(encoded)
    }
}




public object FfiConverterTypeCheckOutput: FfiConverterRustBuffer<CheckOutput> {
    override fun read(buf: ByteBuffer): CheckOutput {
        return CheckOutput(
            FfiConverterTypeCheckStatus.read(buf),
            FfiConverterString.read(buf),
        )
    }

    override fun allocationSize(value: CheckOutput): ULong = (
            FfiConverterTypeCheckStatus.allocationSize(value.`status`) +
            FfiConverterString.allocationSize(value.`detail`)
    )

    override fun write(value: CheckOutput, buf: ByteBuffer) {
        FfiConverterTypeCheckStatus.write(value.`status`, buf)
        FfiConverterString.write(value.`detail`, buf)
    }
}





public object FfiConverterTypeCheckStatus: FfiConverterRustBuffer<CheckStatus> {
    override fun read(buf: ByteBuffer): CheckStatus = try {
        CheckStatus.entries[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: CheckStatus): ULong = 4UL

    override fun write(value: CheckStatus, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}


public fun `checkArmTiming`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_arm_timing(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkBpfIfaceMap`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_bpf_iface_map(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkDirectSyscall`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_direct_syscall(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkGetifaddrs`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_getifaddrs(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkGetsocknameSpoof`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_getsockname_spoof(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkGetsockoptBind`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_getsockopt_bind(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkGsoAsymmetry`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_gso_asymmetry(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkInetDiag`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_inet_diag(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkIoctlAlternative`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_ioctl_alternative(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkIoctlSiocgifconf`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_ioctl_siocgifconf(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkIoctlSiocgifflags`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_ioctl_siocgifflags(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkIoctlSiocgifmtu`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_ioctl_siocgifmtu(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkIpv6LinkLocalBruteforce`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_ipv6_link_local_bruteforce(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkLoopbackBindConflict`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_loopback_bind_conflict(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkNetlinkAnonymousRoute`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_netlink_anonymous_route(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkNetlinkGetlink`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_netlink_getlink(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkNetlinkGetneigh`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_netlink_getneigh(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkNetlinkGetroute`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_netlink_getroute(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkNetlinkGetrule`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_netlink_getrule(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkPmtuCachePoisoning`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_pmtu_cache_poisoning(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetDev`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_dev(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetFibTrie`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_fib_trie(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetIfInet6`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_if_inet6(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetIpv6Route`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_ipv6_route(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetRoute`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_route(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetTcp`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_tcp(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetTcp6`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_tcp6(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetUdp`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_udp(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcNetUdp6`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_net_udp6(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkProcSysNetConf`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_proc_sys_net_conf(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkQdiscByIfindex`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_qdisc_by_ifindex(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkRtmGetlinkTrimOracle`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_rtm_getlink_trim_oracle(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkSysClassNet`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_sys_class_net(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkSystemProperties`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_system_properties(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkTcpInfoMss`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_tcp_info_mss(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkTcpMss`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_tcp_mss(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkTimestampingHw`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_timestamping_hw(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkTracerouteRtt`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_traceroute_rtt(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkUdpPmtu`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_udp_pmtu(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkUdpQueuePressure`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_udp_queue_pressure(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkUidRouteRulesLeak`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_uid_route_rules_leak(
            uniffiRustCallStatus,
        )
    })
}

public fun `checkUnderlayPortConflict`(): CheckOutput {
    return FfiConverterTypeCheckOutput.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_check_underlay_port_conflict(
            uniffiRustCallStatus,
        )
    })
}

/**
 * Parse /proc/net/dev and return raw per-interface TX/RX byte counters as CSV.
 *
 * Format: one line per interface, "ifname,tx_bytes,rx_bytes".
 * Called from Kotlin to get ground-truth stats bypassing Java SELinux restrictions.
 * Returns an empty string if the file is unreadable (SELinux denial or not available).
 */
public fun `parseProcNetDevCsv`(): kotlin.String {
    return FfiConverterString.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_vpnhide_checks_fn_func_parse_proc_net_dev_csv(
            uniffiRustCallStatus,
        )
    })
}


// Async support