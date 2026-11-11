// MLB 도전 카드 선배 사진 편집본 만들기(macOS Vision). 쓰는 법은 같은 폴더 README.md.
//
//   cut <원본 사진> <출력.jpg>   배경을 지워 흰 바탕에 놓고, 얼굴 크기·위치를 기준(노시환 KBO 공식 사진)에 맞춘
//                                256×256 정사각형 JPEG로 저장한다
//   measure <사진>               화면처럼 위쪽 정사각형(object-cover object-top)으로 볼 때의 얼굴 위치·크기와 배경 색을 출력한다
//                                (기준 값을 다시 잴 때 쓴다)
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Vision

/// 기준: 노시환 KBO 2026 공식 사진(/photos/kbo-si-hwan-roh, 94×118, 흰 배경)을 위쪽 정사각형으로 볼 때
/// 얼굴(Vision 얼굴 사각형) 높이는 폭의 49.25%, 얼굴 중심은 위에서 57.47%. 가로는 가운데(50%)에 둔다.
let faceCenterX: CGFloat = 0.5
let faceCenterY: CGFloat = 0.5747
let faceHeight: CGFloat = 0.4925
let outputSize = 256

func load(_ path: String) -> CGImage {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        fail("사진을 읽지 못했다: \(path)")
    }
    return image
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(1)
}

/// 가장 큰 얼굴 사각형(픽셀, 위쪽 원점)
func face(_ image: CGImage) -> CGRect {
    let request = VNDetectFaceRectanglesRequest()
    try! VNImageRequestHandler(cgImage: image).perform([request])
    guard let found = request.results?.max(by: { $0.boundingBox.width < $1.boundingBox.width }) else {
        fail("얼굴을 찾지 못했다")
    }
    let w = CGFloat(image.width), h = CGFloat(image.height), box = found.boundingBox
    return CGRect(x: box.minX * w, y: (1 - box.maxY) * h, width: box.width * w, height: box.height * h)
}

/// 사람(전경)만 남기고 배경을 투명하게 지운 사진
func cutOut(_ image: CGImage) -> CGImage {
    let request = VNGenerateForegroundInstanceMaskRequest()
    let handler = VNImageRequestHandler(cgImage: image)
    try! handler.perform([request])
    guard let mask = request.results?.first else { fail("사람을 찾지 못했다") }
    let masked = try! mask.generateMaskedImage(ofInstances: mask.allInstances, from: handler,
                                               croppedToInstancesExtent: false)
    let ci = CIImage(cvPixelBuffer: masked)
    return CIContext().createCGImage(ci, from: ci.extent)!
}

func context(_ width: Int, _ height: Int) -> CGContext {
    CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
              space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
}

let args = CommandLine.arguments
guard args.count >= 3 else { fail("쓰는 법: posting-photo cut <원본> <출력.jpg> | posting-photo measure <사진>") }

switch args[1] {
case "measure":
    let image = load(args[2])
    let r = face(image)
    let side = CGFloat(image.width) // 화면은 폭에 맞춘 위쪽 정사각형을 보여 준다
    print(String(format: "얼굴 중심 x=%.4f y=%.4f, 얼굴 높이 %.4f (사진 %d×%d)",
                 r.midX / side, r.midY / side, r.height / side, image.width, image.height))
    let ctx = context(image.width, image.height)
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    let p = ctx.data!.assumingMemoryBound(to: UInt8.self)
    let i = (2 * image.width + 2) * 4 // 왼쪽 위 귀퉁이(비트맵 메모리는 맨 윗줄부터)
    print("배경(왼쪽 위) = \(p[i]),\(p[i + 1]),\(p[i + 2])")
case "cut":
    guard args.count >= 4 else { fail("쓰는 법: posting-photo cut <원본> <출력.jpg>") }
    let image = load(args[2])
    let r = face(image)
    let cut = cutOut(image)

    // 흰 바탕 정사각형에 얼굴 높이·중심을 기준대로 맞춰 그린다(CG는 아래쪽 원점)
    let s = CGFloat(outputSize)
    let scale = faceHeight * s / r.height
    let ctx = context(outputSize, outputSize)
    ctx.interpolationQuality = .high
    ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: s, height: s))
    let width = CGFloat(image.width) * scale, height = CGFloat(image.height) * scale
    let left = faceCenterX * s - r.midX * scale
    let top = faceCenterY * s - r.midY * scale
    ctx.draw(cut, in: CGRect(x: left, y: s - top - height, width: width, height: height))

    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[3]) as CFURL,
                                               UTType.jpeg.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, ctx.makeImage()!, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
    guard CGImageDestinationFinalize(dest) else { fail("저장하지 못했다: \(args[3])") }
    // 원본 아래 끝이 정사각형 아래 끝보다 위면 바닥에 흰 띠가 생긴다(원본이 너무 작게 찍힌 사진)
    if top + height < s { print("주의: 원본 아래 끝이 정사각형 바닥보다 위다(\(Int(top + height))/\(outputSize))") }
    print("\(args[3]) (얼굴 \(Int(r.height))px → \(Int(faceHeight * s))px)")
default:
    fail("cut 또는 measure")
}
