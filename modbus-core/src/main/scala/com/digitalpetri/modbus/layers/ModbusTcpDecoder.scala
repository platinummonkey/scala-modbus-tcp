/*
 * Copyright 2014 Kevin Herron
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpetri.modbus.layers

import java.util

import com.codahale.metrics.Counter
import com.digitalpetri.modbus._
import com.digitalpetri.modbus.serialization.ModbusPduDecoder
import com.typesafe.scalalogging.slf4j.StrictLogging
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

class ModbusTcpDecoder(decoder: ModbusPduDecoder,
                       instanceId: Option[String],
                       decodingErrorCount: Counter,
                       unsupportedPduCount: Counter) extends ByteToMessageDecoder with StrictLogging {

  def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: util.List[Object]): Unit = {
    var startIndex = in.readerIndex()

    while (in.readableBytes() >= ModbusTcpDecoder.HeaderLength &&
           in.readableBytes() >= getLength(in, startIndex) + ModbusTcpDecoder.HeaderSize) {

      try {
        val header  = MbapHeader.decode(in)
        val pdu     = decoder.decode(in)
        val payload = TcpPayload(header.transactionId, header.unitId, pdu)

        payload.pdu match {
          /*
           * The ModbusPdu is a request or response this library doesn't support. This is not the same as the master
           * or slave implementation not being able to handle the request/response; in this case there isn't even an
           * encoder/decoder implemented for it yet!
           */
          case UnsupportedPdu(functionCode) =>
            unsupportedPduCount.inc()

            val response = ExceptionResponse(functionCode, IllegalFunction)
            ctx.channel().writeAndFlush(TcpPayload(payload.transactionId, payload.unitId, response))

            // Advance past any bytes we should have read but didn't...
            val endIndex = startIndex + getLength(in, startIndex) + 6
            in.readerIndex(endIndex)

          /*
           * Decoding the header and ModbusPdu was successful; deliver it to the next layer.
           */
          case _ => out.add(payload)
        }
      } catch {
        case ex: Throwable =>
          logger.debug(s"Could not decode header and pdu: ${ex.getMessage}")
          decodingErrorCount.inc()

          // Advance past any bytes we should have read but didn't...
          val endIndex = startIndex + getLength(in, startIndex) + 6
          in.readerIndex(endIndex)
      }

      startIndex = in.readerIndex()
    }
  }

  private def getLength(in: ByteBuf, startIndex: Int): Int = {
    in.getUnsignedShort(startIndex + ModbusTcpDecoder.LengthFieldIndex)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    logger.error(s"Exception caught; closing channel: ${cause.getMessage}", cause)
    ctx.close()
  }

}

object ModbusTcpDecoder {

  val HeaderLength = MbapHeader.Length
  val HeaderSize = 6
  val LengthFieldIndex = 4

}


