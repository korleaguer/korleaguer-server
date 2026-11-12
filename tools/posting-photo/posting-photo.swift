// MLB 도전 카드 선배 사진 편집본 만들기(macOS Vision). 쓰는 법은 같은 폴더 README.md.
//
//   cut <원본 사진> <출력.jpg>   배경을 지워 흰 바탕에 놓고, 얼굴 크기·위치를 기준(노시환 KBO 공식 사진)에 맞춘
//                                256×256 정사각형 JPEG로 저장한다
//   silo <원본> <출력.png>       선수 페이지 과거 소속팀(KBO) 사진 편집본: 배경 없는 MLB 공식 사진(headshot/silo)과 같은 틀
//                                (투명 바탕 288×288, 얼굴 크기·위치도 같게). 원본 배경이 이미 투명하면 그대로 쓰고, 아니면 지운다
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

/// silo 기준: 배경 없는 MLB 공식 사진(headshot/silo, 360×360) 5장(이정후·김혜성·김하성·송성문·고우석, 2026-11-13)의
/// 얼굴 높이는 폭의 49.8~52.3%(평균 51.1%), 얼굴 중심은 위에서 49.8~53.6%(평균 51.6%). 가로는 가운데.
let siloFaceCenterX: CGFloat = 0.5
let siloFaceCenterY: CGFloat = 0.516
let siloFaceHeight: CGFloat = 0.511
/// 선수 페이지 원(지름 96px)의 3배
let siloOutputSize = 288

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

/// Lanczos로 scale배 키운다(작은 원본을 배경 지우기 전에 키워 두면 경계가 계단처럼 보이지 않는다)
func enlarge(_ image: CGImage, _ scale: CGFloat) -> CGImage {
    let filter = CIFilter(name: "CILanczosScaleTransform")!
    filter.setValue(CIImage(cgImage: image), forKey: kCIInputImageKey)
    filter.setValue(scale, forKey: kCIInputScaleKey)
    filter.setValue(1.0, forKey: kCIInputAspectRatioKey)
    let out = filter.outputImage!
    let size = CGRect(x: 0, y: 0, width: (CGFloat(image.width) * scale).rounded(), height: (CGFloat(image.height) * scale).rounded())
    return CIContext().createCGImage(out, from: size)!
}

/// 왼쪽 위 귀퉁이가 투명한가(배경을 이미 지운 사진)
func transparentCorner(_ image: CGImage) -> Bool {
    let ctx = context(image.width, image.height)
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    let p = ctx.data!.assumingMemoryBound(to: UInt8.self)
    return p[(1 * image.width + 1) * 4 + 3] == 0
}

let args = CommandLine.arguments
guard args.count >= 3 else { fail("쓰는 법: posting-photo cut <원본> <출력.jpg> | silo <원본> <출력.png> | measure <사진>") }

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
case "silo":
    guard args.count >= 4 else { fail("쓰는 법: posting-photo silo <원본> <출력.png>") }
    let original = load(args[2])
    let r0 = face(original)
    let s = CGFloat(siloOutputSize)
    let scale = siloFaceHeight * s / r0.height
    // 먼저 출력 크기로 키운 뒤(얼굴 위치도 그 크기에서 다시 잰다) 배경을 지우고 1:1로 그린다
    let image = enlarge(original, scale)
    let r = face(image)
    let cut = transparentCorner(original) ? image : cutOut(image)

    // 투명 바탕 정사각형에 얼굴 높이·중심을 MLB silo 사진과 같게 그린다(CG는 아래쪽 원점)
    let ctx = context(siloOutputSize, siloOutputSize)
    ctx.interpolationQuality = .high
    let k = siloFaceHeight * s / r.height // 다시 잰 얼굴로 남은 차이만 맞춘다(거의 1)
    let width = CGFloat(image.width) * k, height = CGFloat(image.height) * k
    let left = siloFaceCenterX * s - r.midX * k
    let top = siloFaceCenterY * s - r.midY * k
    ctx.draw(cut, in: CGRect(x: left, y: s - top - height, width: width, height: height))

    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: args[3]) as CFURL,
                                               UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
    guard CGImageDestinationFinalize(dest) else { fail("저장하지 못했다: \(args[3])") }
    if top + height < s { print("주의: 원본 아래 끝이 정사각형 바닥보다 위다(\(Int(top + height))/\(siloOutputSize))") }
    print("\(args[3]) (얼굴 \(Int(r0.height))px → \(Int(siloFaceHeight * s))px, \(String(format: "%.2f", scale))배)")
default:
    fail("cut, silo 또는 measure")
}
