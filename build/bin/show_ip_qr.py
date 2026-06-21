import socket
import qrcode
import sys

def get_local_ip():
    try:
        # Connect to a dummy external address to obtain the primary local IP interface
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def generate_qr():
    ip = get_local_ip()
    print("======================================================")
    print(f" Your Synergy Server IP Address: {ip}")
    print(" Scan the QR code below on your phone to copy the IP:")
    print("======================================================")
    print("")
    
    qr = qrcode.QRCode(version=1, box_size=1, border=1)
    qr.add_data(ip)
    qr.make(fit=True)
    
    # Print QR code directly in the terminal using characters
    qr.print_ascii(invert=True)
    print("")
    print("======================================================")

if __name__ == "__main__":
    generate_qr()
