// @ts-check
import net from 'node:net'

const BLOCK_HEADER = 0x01
const BLOCK_SESSIONINFO = 0x10
const BLOCK_EXECUTIONDATA = 0x11
const BLOCK_CMDOK = 0x20
const BLOCK_CMDDUMP = 0x40
const MAGIC_NUMBER = 0xc0c0
const FORMAT_VERSION = 0x1007

/**
 * A minimal client of JaCoCo's `output=tcpserver` mode: connects, asks for a dump without reset, and returns the
 * execution data it receives, as JaCoCo's `ExecDumpClient` does. The client speaks first, with the header of the
 * execution data format JaCoCo 0.7.5 and later write; an agent of another format answers with its own header, which
 * fails here by name.
 *
 * @param {number} port
 * @param {number} [timeoutMillis]
 * @returns {Promise<Map<string, boolean[]>>} each class's VM name and probes
 */
export function dumpCoverage(port, timeoutMillis = 30_000) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({host: '127.0.0.1', port}, () => {
      const command = Buffer.alloc(8)
      command.writeUInt8(BLOCK_HEADER, 0)
      command.writeUInt16BE(MAGIC_NUMBER, 1)
      command.writeUInt16BE(FORMAT_VERSION, 3)
      command.writeUInt8(BLOCK_CMDDUMP, 5)
      command.writeUInt8(1, 6)
      command.writeUInt8(0, 7)
      socket.write(command)
    })
    let buffer = Buffer.alloc(0)
    const timer = setTimeout(
      () => fail(new Error(`No JaCoCo dump from 127.0.0.1:${port} in ${timeoutMillis} ms`)),
      timeoutMillis
    )

    /** @param {Error} error */
    function fail(error) {
      clearTimeout(timer)
      socket.destroy()
      reject(error)
    }

    socket.on('error', fail)
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      if (buffer.length < 5) return
      if (buffer[0] !== BLOCK_HEADER || buffer.readUInt16BE(1) !== MAGIC_NUMBER) {
        fail(new Error('Not a JaCoCo execution data stream'))
        return
      }
      if (buffer.readUInt16BE(3) !== FORMAT_VERSION) {
        fail(new Error(`JaCoCo execution data format 0x${buffer.readUInt16BE(3).toString(16)}, not 0x1007`))
        return
      }
      let classes
      try {
        classes = parse(buffer.subarray(5))
      } catch (error) {
        // Node's buffer bounds errors are RangeErrors too: the dump is still arriving.
        if (error instanceof RangeError) return
        fail(/** @type {Error} */ (error))
        return
      }
      clearTimeout(timer)
      socket.end()
      resolve(classes)
    })
  })
}

/**
 * Parses JaCoCo's blocks after the header up to the command acknowledgement; throws a RangeError while incomplete.
 *
 * @param {Buffer} data
 * @returns {Map<string, boolean[]>}
 */
function parse(data) {
  const classes = new Map()
  let offset = 0
  const utf = () => {
    const length = data.readUInt16BE(offset)
    offset += 2
    if (offset + length > data.length) throw new RangeError('incomplete string')
    const value = data.toString('utf8', offset, offset + length)
    offset += length
    return value
  }
  const varInt = () => {
    let value = 0
    let shift = 0
    for (;;) {
      if (offset >= data.length) throw new RangeError('incomplete varint')
      const byte = data[offset++]
      value |= (byte & 0x7f) << shift
      if ((byte & 0x80) === 0) return value
      shift += 7
    }
  }
  while (offset < data.length) {
    const block = data[offset++]
    switch (block) {
      case BLOCK_HEADER:
        data.readUInt16BE(offset + 2)
        offset += 4
        break
      case BLOCK_SESSIONINFO:
        utf()
        data.readBigInt64BE(offset + 8)
        offset += 16
        break
      case BLOCK_EXECUTIONDATA: {
        data.readBigInt64BE(offset)
        offset += 8
        const name = utf()
        const length = varInt()
        const bytes = Math.ceil(length / 8)
        if (offset + bytes > data.length) throw new RangeError('incomplete probes')
        const probes = []
        for (let index = 0; index < length; index++) {
          probes.push((data[offset + (index >> 3)] & (1 << (index & 7))) !== 0)
        }
        offset += bytes
        classes.set(name, probes)
        break
      }
      case BLOCK_CMDOK:
        return classes
      default:
        throw new Error(`Unexpected JaCoCo block type 0x${block.toString(16)}`)
    }
  }
  throw new RangeError('no acknowledgement yet')
}
