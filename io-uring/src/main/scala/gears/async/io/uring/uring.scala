package gears.async.io
package uring

import scala.scalanative.libc.stddef._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

@link("uring")
@extern
private[uring] object uring {
  final val IORING_OP_NOP = 0
  final val IORING_OP_READV = 1
  final val IORING_OP_WRITEV = 2
  final val IORING_OP_FSYNC = 3
  final val IORING_OP_POLL_ADD = 6
  final val IORING_OP_SENDMSG = 9
  final val IORING_OP_RECVMSG = 10
  final val IORING_OP_ACCEPT = 13
  final val IORING_OP_READ = 22
  final val IORING_OP_WRITE = 23

  type __u8 = CUnsignedChar
  type __u16 = CUnsignedShort
  type __s32 = CInt
  type __u32 = CUnsignedInt
  type __u64 = CUnsignedLongLong

  type __kernel_rwf_t = CUnsignedInt

  type iovec = CStruct2[Ptr[Byte], size_t]
  type msghdr = CStruct7[Ptr[Byte], CUnsignedInt, Ptr[iovec], size_t, Ptr[Byte], size_t, CInt]

  type io_uring = CStruct0

  type io_uring_cqe = CStruct3[__u64, __s32, __u32]

  type io_uring_sqe =
    CStruct10[__u8, __u8, __u16, __s32, __u64, __u64, __u32, __u32, __u64, CArray[__u64, Nat._3]]

  @name("gears_buf_ring_setup")
  def buf_ring_setup(
      ring: Ptr[io_uring],
      entries: CUnsignedInt,
      bgid: CInt,
      base: Ptr[Byte],
      size: CUnsignedInt,
      err: Ptr[CInt]
  ): Ptr[Byte] = extern

  @name("gears_buf_ring_free")
  def buf_ring_free(ring: Ptr[io_uring], br: Ptr[Byte], entries: CUnsignedInt, bgid: CInt): CInt = extern

  @name("gears_buf_ring_recycle")
  def buf_ring_recycle(br: Ptr[Byte], entries: CUnsignedInt, base: Ptr[Byte], size: CUnsignedInt, bid: CInt): Unit =
    extern

  @name("gears_prep_recv_multishot")
  def prep_recv_multishot(sqe: Ptr[io_uring_sqe], fd: CInt, bgid: CInt, user_data: __u64): Unit = extern

  @name("gears_io_uring_init")
  def io_uring_init(entries: CUnsignedInt, ring: Ptr[io_uring], completions: CUnsignedInt): CInt = extern

  def io_uring_queue_exit(ring: Ptr[io_uring]): Unit = extern

  @name("gears_io_uring_get_sqe")
  def io_uring_get_sqe(ring: Ptr[io_uring]): Ptr[io_uring_sqe] = extern

  def io_uring_submit(ring: Ptr[io_uring]): CInt = extern

  @name("gears_io_uring_sqe_pending")
  def io_uring_sqe_pending(ring: Ptr[io_uring], sqe: Ptr[io_uring_sqe], user_data: __u64): CInt = extern

  @name("gears_io_uring_sq_ready")
  def io_uring_sq_ready(ring: Ptr[io_uring]): CUnsignedInt = extern

  @name("gears_io_uring_submit_and_wait")
  @blocking
  def io_uring_submit_and_wait(ring: Ptr[io_uring], nanoseconds: CLongLong): CInt = extern

  def io_uring_peek_batch_cqe(
      ring: Ptr[io_uring],
      cqes: Ptr[Ptr[io_uring_cqe]],
      count: CUnsignedInt
  ): CUnsignedInt = extern

  @name("gears_io_uring_cq_advance")
  def io_uring_cq_advance(ring: Ptr[io_uring], nr: CUnsignedInt): Unit = extern

  @name("gears_io_uring_cancel_now")
  @blocking
  def io_uring_cancel_now(ring: Ptr[io_uring], user_data: __u64, wait: CInt): CInt = extern

  @name("gears_io_uring_cancel_fd")
  @blocking
  def io_uring_cancel_fd(ring: Ptr[io_uring], fd: CInt): CInt = extern

  @name("gears_io_uring_size")
  def io_uring_size(): CSize = extern

  @name("gears_io_uring_features")
  def io_uring_features(ring: Ptr[io_uring]): CUnsignedInt = extern

  @name("gears_io_uring_cq_overflowing")
  def io_uring_cq_overflowing(ring: Ptr[io_uring]): CInt = extern

  @name("gears_io_uring_spin_check")
  def io_uring_spin_check(ring: Ptr[io_uring]): CInt = extern

  @name("gears_io_uring_get_events")
  def io_uring_get_events(ring: Ptr[io_uring]): CInt = extern

  final val IORING_FEAT_NODROP = 1 << 1
  final val IORING_FEAT_EXT_ARG = 1 << 8

}

private[uring] object uringStructOps {

  import uring._

  def io_uring_prep_rw(
      op: Int,
      sqe: Ptr[io_uring_sqe],
      fd: Int,
      addr: Ptr[?],
      len: CUnsignedInt,
      offset: __u64
  ): Unit = {
    sqe.opcode = op.toUByte
    sqe.flags = 0.toUByte
    sqe.ioprio = 0.toUShort
    sqe.fd = fd
    sqe.off = offset
    sqe.addr = if (addr eq null) 0.toULong else addr.toLong.toULong
    sqe.len = len
    sqe.rw_flags = 0.toUInt
    sqe.clearTail()
  }

  def io_uring_prep_nop(sqe: Ptr[io_uring_sqe]): Unit =
    io_uring_prep_rw(IORING_OP_NOP, sqe, -1, null, 0.toUInt, 0.toULong)

  def io_uring_prep_accept(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      addr: Ptr[sockaddr],
      addrlen: Ptr[socklen_t],
      flags: CInt
  ): Unit = {
    io_uring_prep_rw(
      IORING_OP_ACCEPT,
      sqe,
      fd,
      addr,
      0.toUInt,
      if (addrlen eq null) 0.toULong else addrlen.toLong.toULong
    )
    sqe.accept_flags = flags.toUInt
  }

  def io_uring_prep_fsync(sqe: Ptr[io_uring_sqe], fd: CInt): Unit =
    io_uring_prep_rw(IORING_OP_FSYNC, sqe, fd, null, 0.toUInt, 0.toULong)

  def io_uring_prep_poll_add(sqe: Ptr[io_uring_sqe], fd: CInt, mask: CUnsignedInt): Unit = {
    io_uring_prep_rw(IORING_OP_POLL_ADD, sqe, fd, null, 0.toUInt, 0.toULong)
    sqe.poll32_events = mask
  }

  def io_uring_prep_read(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      buf: Ptr[Byte],
      nbytes: CUnsignedInt,
      offset: __u64
  ): Unit = io_uring_prep_rw(IORING_OP_READ, sqe, fd, buf, nbytes, offset)

  def io_uring_prep_write(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      buf: Ptr[Byte],
      nbytes: CUnsignedInt,
      offset: __u64
  ): Unit = io_uring_prep_rw(IORING_OP_WRITE, sqe, fd, buf, nbytes, offset)

  def io_uring_prep_readv(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      iovecs: Ptr[iovec],
      nr_vecs: CUnsignedInt,
      offset: __u64
  ): Unit = io_uring_prep_rw(IORING_OP_READV, sqe, fd, iovecs, nr_vecs, offset)

  def io_uring_prep_writev(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      iovecs: Ptr[iovec],
      nr_vecs: CUnsignedInt,
      offset: __u64
  ): Unit = io_uring_prep_rw(IORING_OP_WRITEV, sqe, fd, iovecs, nr_vecs, offset)

  def io_uring_prep_sendmsg(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      msg: Ptr[msghdr],
      flags: CInt
  ): Unit = {
    io_uring_prep_rw(IORING_OP_SENDMSG, sqe, fd, msg.asInstanceOf[Ptr[Byte]], 1.toUInt, 0.toULong)
    sqe.msg_flags = flags.toUInt
  }

  def io_uring_prep_recvmsg(
      sqe: Ptr[io_uring_sqe],
      fd: CInt,
      msg: Ptr[msghdr],
      flags: CInt
  ): Unit = {
    io_uring_prep_rw(IORING_OP_RECVMSG, sqe, fd, msg.asInstanceOf[Ptr[Byte]], 1.toUInt, 0.toULong)
    sqe.msg_flags = flags.toUInt
  }

  implicit final class io_uring_sqeOps(val io_uring_sqe: Ptr[io_uring_sqe]) extends AnyVal {
    private inline def at[T](offset: Int): Ptr[T] = (io_uring_sqe.asInstanceOf[Ptr[Byte]] + offset).asInstanceOf[Ptr[T]]

    inline def opcode: __u8 = !at[__u8](0)
    inline def opcode_=(opcode: __u8): Unit = !at[__u8](0) = opcode

    inline def flags: __u8 = !at[__u8](1)
    inline def flags_=(flags: __u8): Unit = !at[__u8](1) = flags

    inline def ioprio: __u16 = !at[__u16](2)
    inline def ioprio_=(ioprio: __u16): Unit = !at[__u16](2) = ioprio

    inline def fd: __s32 = !at[__s32](4)
    inline def fd_=(fd: __s32): Unit = !at[__s32](4) = fd

    inline def off: __u64 = !at[__u64](8)
    inline def off_=(off: __u64): Unit = !at[__u64](8) = off

    inline def addr: __u64 = !at[__u64](16)
    inline def addr_=(addr: __u64): Unit = !at[__u64](16) = addr

    inline def len: __u32 = !at[__u32](24)
    inline def len_=(len: __u32): Unit = !at[__u32](24) = len

    inline def rw_flags: __kernel_rwf_t = !at[__kernel_rwf_t](28)
    inline def rw_flags_=(rw_flags: __kernel_rwf_t): Unit = !at[__kernel_rwf_t](28) = rw_flags
    inline def msg_flags: __u32 = !at[__u32](28)
    inline def msg_flags_=(msg_flags: __u32): Unit = !at[__u32](28) = msg_flags
    inline def accept_flags: __u32 = !at[__u32](28)
    inline def accept_flags_=(accept_flags: __u32): Unit = !at[__u32](28) = accept_flags
    inline def poll32_events: __u32 = !at[__u32](28)
    inline def poll32_events_=(poll32_events: __u32): Unit = !at[__u32](28) = poll32_events

    inline def user_data: __u64 = !at[__u64](32)
    inline def user_data_=(user_data: __u64): Unit = !at[__u64](32) = user_data

    inline def clearTail(): Unit = {
      !at[Long](40) = 0L
      !at[Long](48) = 0L
      !at[Long](56) = 0L
    }
  }

  implicit final class io_uring_cqeOps(val io_uring_cqe: Ptr[io_uring_cqe]) extends AnyVal {
    private inline def at[T](offset: Int): Ptr[T] = (io_uring_cqe.asInstanceOf[Ptr[Byte]] + offset).asInstanceOf[Ptr[T]]

    inline def user_data: __u64 = !at[__u64](0)
    inline def user_data_=(user_data: __u64): Unit = !at[__u64](0) = user_data
    inline def res: __s32 = !at[__s32](8)
    inline def res_=(res: __s32): Unit = !at[__s32](8) = res
    inline def flags: __u32 = !at[__u32](12)
    inline def flags_=(flags: __u32): Unit = !at[__u32](12) = flags
  }

  implicit final class msghdrOps(val msghdr: Ptr[msghdr]) extends AnyVal {
    inline def msg_name: Ptr[Byte] = msghdr._1
    inline def msg_name_=(msg_name: Ptr[Byte]): Unit = !msghdr.at1 = msg_name
    inline def msg_namelen: CUnsignedInt = msghdr._2
    inline def msg_namelen_=(msg_namelen: CUnsignedInt): Unit = !msghdr.at2 = msg_namelen
    inline def msg_iov: Ptr[iovec] = msghdr._3
    inline def msg_iov_=(msg_iov: Ptr[iovec]): Unit = !msghdr.at3 = msg_iov
    inline def msg_iovlen: size_t = msghdr._4
    inline def msg_iovlen_=(msg_iovlen: size_t): Unit = !msghdr.at4 = msg_iovlen
    inline def msg_control: Ptr[Byte] = msghdr._5
    inline def msg_control_=(msg_control: Ptr[Byte]): Unit = !msghdr.at5 = msg_control
    inline def msg_controllen: size_t = msghdr._6
    inline def msg_controllen_=(msg_controllen: size_t): Unit = !msghdr.at6 = msg_controllen
    inline def msg_flags: CInt = msghdr._7
    inline def msg_flags_=(msg_flags: CInt): Unit = !msghdr.at7 = msg_flags
  }

}
