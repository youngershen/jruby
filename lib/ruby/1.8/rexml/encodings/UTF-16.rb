module REXML
  module Encoding
    class UTF_16Encoder
      def encode content
        array_utf8 = content.unpack("U*")
        array_enc = []
        array_utf8.each do |num|
          if ((num>>16) > 0)
            array_enc << 0
            array_enc << ??
          else
            array_enc << (num >> 8)
            array_enc << (num & 0xFF)
          end
        end
        array_enc.pack('C*')
      end
      
      def decode(str)
        str = str[2..-1] if /^\376\377/n =~ str
        array_enc=str.unpack('C*')
        array_utf8 = []
        0.step(array_enc.size-1, 2){|i| 
          array_utf8 << (array_enc.at(i+1) + array_enc.at(i)*0x100)
        }
        array_utf8.pack('U*')
      end
    end

    utf_16 = UTF_16Encoder.new
    register(UTF_16) do |obj|
      obj.encoder = utf_16
    end
  end
end
