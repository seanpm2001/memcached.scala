package com.bitlove.memcached

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.InetSocketAddress
import com.bitlove.memcached.protocol._

class Memcached(host: String, port: Int) {
  class ProtocolError(message: String) extends Error(message)

  private val addr            = new InetSocketAddress(host, port)
  private val channel         = SocketChannel.open(addr)
  private val header          = ByteBuffer.allocate(24)

  def get(key: Array[Byte]): Option[Array[Byte]] = {
    channel.write(RequestBuilder.get(key))
    handleResponse(Ops.Get, handleGetResponse)
  }

  def set(key:   Array[Byte],
          value: Array[Byte],
          ttl:   Int = 0,
          flags: Int = 0) = {
    channel.write(RequestBuilder.storageRequest(Ops.Set, key, value, flags, ttl))
    handleResponse(Ops.Set, handleStorageResponse)
  }

  private def handleStorageResponse(header: ByteBuffer, body: ByteBuffer): Boolean = {
    header.getShort(6) match {
      case Status.Success   => true
      case Status.KeyExists => false
      case Status.NotStored => false
      case code             => throw new ProtocolError("Unexpected status code %d".format(code))
    }
  }

  private def handleGetResponse(header: ByteBuffer, body: ByteBuffer): Option[Array[Byte]] = {
    val extras  = header.get(4).toInt

    header.getShort(6) match {
      case Status.Success => Some(body.array.slice(extras, body.capacity))
      case _              => None
    }
  }

  private def handleResponse[T](opcode:  Byte,
                                handler: (ByteBuffer, ByteBuffer) => T): T = {
    fillHeader
    verifyMagic
    verifyOpcode(opcode)
    handler(header, fillBodyFromHeader)
  }

  private def fillHeader: Unit = {
    header.clear
    fill(header, 24)
  }

  private def fillBodyFromHeader: ByteBuffer = {
    val len  = header.getInt(8)
    val body = ByteBuffer.allocate(len)
    fill(body, len)
    body
  }

  private def fill(buffer: ByteBuffer, len: Int): Unit = {
    var read = 0

    while (read < len) {
      read += channel.read(buffer)
    }
  }

  private def verifyMagic = {
    header.get(0) match {
      case Packets.Response => ()
      case byte             => {
        throw new ProtocolError("Unexpected header magic 0x%x".format(byte))
      }
    }
  }

  private def verifyOpcode(opcode: Byte) = {
    header.get(1) match {
      case x if x == opcode => ()
      case otherByte        => {
        throw new ProtocolError("Unexpected opcode 0x%x".format(otherByte))
      }
    }
  }
}