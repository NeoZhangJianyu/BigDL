/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intel.analytics.bigdl.nn.mkldnn

import com.intel.analytics.bigdl.mkl.Memory
import com.intel.analytics.bigdl.nn.mkldnn.Phase.{InferencePhase, TrainingPhase}
import com.intel.analytics.bigdl.tensor.{DnnTensor, Tensor}
import com.intel.analytics.bigdl.numeric.NumericFloat
import com.intel.analytics.bigdl.utils.Engine
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

class ReorderMemorySpec extends FlatSpec with Matchers with BeforeAndAfter {
  "lenet5 with reorder" should "works no segment fault" in {
    // after upgrade to mkldnn v0.17, the thread local variables will cause spark to stop
    // this test case tests the single pool
    System.setProperty("bigdl.localMode", "true")
    System.setProperty("bigdl.engineType", "mkldnn")
    System.setProperty("bigdl.coreNumber", "1")
    System.setProperty("bigdl.utils.Engine.defaultPoolSize", "1")
    Engine.init

    println(System.getProperty("bigdl.engineType"))

    val inputShape = Array(100, 1, 28, 28)
    val outputShape = Array(100, 10)

    val model = Sequential()
      .add(Input(inputShape, Memory.Format.nchw))
      .add(SpatialConvolution(1, 20, 5, 5).setName("conv1"))
      .add(SpatialBatchNormalization(20).setName("bn1"))
      .add(MaxPooling(2, 2, 2, 2).setName("pool1"))
      .add(SpatialConvolution(20, 50, 5, 5).setName("conv2"))
      .add(MaxPooling(2, 2, 2, 2).setName("pool2"))
      .add(Linear(50 * 4 * 4, 500).setName("ip1"))
      .add(ReLU().setName("relu1"))
      .add(Linear(500, 10).setName("ip2"))
      .add(ReorderMemory(HeapData(outputShape, Memory.Format.nc)))

    val input = Tensor[Float](inputShape).rand(-1, 1)
    val gradOutput = Tensor[Float](outputShape).rand(-1, 1)

    // we need to compile the model in the invokeAndWait2 threads.
    Engine.dnnComputing.invokeAndWait2((0 until 1).map(i =>
      () => {
        model.compile(TrainingPhase)
      }))

    Engine.dnnComputing.invokeAndWait2((0 until 1).map(i =>
      () => {
        println(s"${Thread.currentThread().getId}")
        model.training()
        model.forward(input)
        model.updateGradInput(input, gradOutput)
        model.accGradParameters(input, gradOutput)
        i
      }
    ), Long.MaxValue)

    for (i <- 0 until 3) {
      Engine.dnnComputing.invokeAndWait2((0 until 1).map(i =>
        () => {
          println(s"${Thread.currentThread().getId}")
          model.training()
          model.forward(input)
          model.updateGradInput(input, gradOutput)
          i
        }
      ), Long.MaxValue)
    }

    System.clearProperty("bigdl.localMode")
    System.clearProperty("bigdl.engineType")
    System.clearProperty("bigdl.coreNumber")
    System.clearProperty("bigdl.utils.Engine.defaultPoolSize")
  }

  "From heap to native" should "be correct" in {
    val layer = ReorderMemory(new NativeData(Array(3, 4), Memory.Format.nc),
      HeapData(Array(3, 4), Memory.Format.nc))
    layer.setRuntime(new MklDnnRuntime())
    layer.initFwdPrimitives(Array(HeapData(Array(3, 4), Memory.Format.nc)), Phase.TrainingPhase)
    layer.initBwdPrimitives(Array(NativeData(Array(3, 4), Memory.Format.nc)), Phase.TrainingPhase)
    val input = Tensor[Float](3, 4).rand()
    val output = layer.forward(input)
    val grad = layer.backward(input, output)
    grad should be(input)
  }

  "From heap to heap" should "be correct" in {
    val layer = ReorderMemory(
      HeapData(Array(3, 4), Memory.Format.nc),
      HeapData(Array(3, 4), Memory.Format.nc),
      HeapData(Array(3, 4), Memory.Format.nc),
      HeapData(Array(3, 4), Memory.Format.nc)
    )
    layer.setRuntime(new MklDnnRuntime())
    layer.initFwdPrimitives(Array(HeapData(Array(3, 4), Memory.Format.nc)), Phase.TrainingPhase)
    layer.initBwdPrimitives(Array(NativeData(Array(3, 4), Memory.Format.nc)), Phase.TrainingPhase)
    val input = Tensor[Float](3, 4).rand()
    val output = layer.forward(input)
    val grad = layer.backward(input, output)
    grad should be(input)
  }

  "Reorder from nhwc to nchw" should "be correct" in {
    val shapeNCHW = Array(4, 3, 7, 7)
    val shapeNHWC = Array(4, 7, 7, 3)
    val inputFormats = HeapData(shapeNHWC, Memory.Format.nhwc)
    val outputFormats = HeapData(shapeNCHW, Memory.Format.nchw)
    val gradInputFormats = HeapData(shapeNCHW, Memory.Format.nchw)
    val gradOutputFormats = HeapData(shapeNHWC, Memory.Format.nhwc)

    val layer = ReorderMemory(inputFormat = inputFormats, outputFormat = outputFormats,
      gradInputFormat = gradInputFormats, gradOutputFomat = gradOutputFormats)

    layer.setRuntime(new MklDnnRuntime())
    layer.initFwdPrimitives(Array(inputFormats), Phase.TrainingPhase)
    layer.initBwdPrimitives(Array(gradOutputFormats), Phase.TrainingPhase)


    val input = Tensor[Float](4, 7, 7, 3).rand()
    val gradOutput = input.clone()
    val output = layer.forward(input).toTensor[Float]
    val grad = layer.backward(input, gradOutput)

    val inputNHWC = input.transpose(2, 4).transpose(3, 4).contiguous().clone()

    inputNHWC should be(output)
    inputNHWC should be(grad)
  }

  "Reorder from nchw to nhwc" should "be correct" in {
    val shapeNCHW = Array(4, 3, 7, 7)
    val shapeNHWC = Array(4, 7, 7, 3)
    val inputFormats = HeapData(shapeNCHW, Memory.Format.nchw)
    val outputFormats = HeapData(shapeNHWC, Memory.Format.nhwc)
    val gradInputFormats = HeapData(shapeNHWC, Memory.Format.nhwc)
    val gradOutputFormats = HeapData(shapeNCHW, Memory.Format.nchw)

    val layer = ReorderMemory(inputFormat = inputFormats, outputFormat = outputFormats,
      gradInputFormat = gradInputFormats, gradOutputFomat = gradOutputFormats)

    layer.setRuntime(new MklDnnRuntime())
    layer.initFwdPrimitives(Array(inputFormats), Phase.TrainingPhase)
    layer.initBwdPrimitives(Array(gradOutputFormats), Phase.TrainingPhase)

    val input = Tensor[Float](4, 3, 7, 7).rand()
    val gradOutput = input.clone()
    val output = layer.forward(input).toTensor[Float]
    val grad = layer.backward(input, gradOutput).toTensor[Float]

    val inputNHWC = input.transpose(2, 3).transpose(3, 4).contiguous().clone()

    // grad.resizeAs(inputNHWC)

    inputNHWC should be(output)
    inputNHWC should be(grad)
  }

  "oihw to Oihw8o" should "work correctly" in {
    // after upgrade mkldnn to v0.17 from v0.15, the LeNet training is broken
    // because Ohwi8o will pad the tensor
    val from = HeapData(Array(20, 1, 5, 5), Memory.Format.oihw)
    val to = HeapData(Array(20, 1, 5, 5), Memory.Format.Ohwi8o)

    val runtime = new MklDnnRuntime
    val reorder = ReorderMemory(to)
    reorder.setRuntime(runtime)
    reorder.initFwdPrimitives(Array(from), TrainingPhase)
    reorder.initBwdPrimitives(Array(to), TrainingPhase)

    val input = Tensor[Float](Array(20, 1, 5, 5)).rand(-1, 1)
    val gradOutput = Tensor[Float](Array(24, 1, 5, 5)).rand(-1, 1)

    val output = reorder.forward(input)
    println(output.toTensor[Float].size().mkString("\t")) // here will be broken before fix

    // we should check the backward Ohwi8o to oihw
    val gradInput = reorder.backward(input, output)

    println(reorder.gradInput.toTensor[Float].size().mkString("\t"))

    output.toTensor[Float].size().deep == Array(24, 1, 5, 5).deep should be (true)
    gradInput.toTensor[Float].size().deep == Array(20, 1, 5, 5).deep should be (true)

    gradInput should be (input)
  }

  "lenet conv1" should "work correctly" in {
    val inputShape = Array(4, 1, 28, 28)
    val outputShape = Array(4, 20, 24, 24)
    val input = Tensor[Float](4, 1, 28, 28).rand(-1, 1)
    val gradOutput = Tensor[Float](outputShape).rand(-1, 1)

    val blas = com.intel.analytics.bigdl.nn.SpatialConvolution(1, 20, 5, 5)
    blas.forward(input)
    blas.backward(input, gradOutput)

    val dnn = Sequential()
      .add(Input(inputShape, Memory.Format.nchw))
      .add(SpatialConvolution(1, 20, 5, 5, initWeight = blas.weight, initBias = blas.bias))
      .add(ReorderMemory(HeapData(outputShape, Memory.Format.nchw)))

    dnn.compile(TrainingPhase)

    dnn.forward(input)
    dnn.updateGradInput(input, gradOutput)
    dnn.accGradParameters(input, gradOutput)

    Equivalent.nearequals(dnn.output.toTensor, blas.output.toTensor, 1e-4) should be (true)
    Equivalent.nearequals(dnn.gradInput.toTensor, blas.gradInput.toTensor, 1e-4) should be (true)

    Equivalent.nearequals(dnn.getParameters()._1, blas.getParameters()._1, 1e-4) should be (true)
    Equivalent.nearequals(dnn.getParameters()._2, blas.getParameters()._2, 1e-4) should be (true)
  }

  "nchw to nChw8c" should "work correctly" in {
    val t1Shape = Array(4, 3, 224, 224)
    val t1 = Tensor[Float](4, 3, 224, 224).rand(-1, 1)
    val t1Format = HeapData(t1Shape, Memory.Format.nchw)

    val t2Shape = Array(4, 3, 224, 224)
    val t2 = Tensor[Float](t2Shape)
    val t2Format = HeapData(t2Shape, Memory.Format.nChw8c)

    val reorder = ReorderMemory(t2Format, t1Format)

    reorder.setRuntime(new MklDnnRuntime)
    reorder.initFwdPrimitives(Array(t1Format), TrainingPhase)
    reorder.initBwdPrimitives(Array(t2Format), TrainingPhase)

    reorder.forward(t1)
    reorder.backward(t1, reorder.output)

    val reorder2 = ReorderMemory(t1Format, t2Format)
    reorder2.setRuntime(new MklDnnRuntime)
    reorder2.initFwdPrimitives(Array(t2Format), InferencePhase)

    reorder2.forward(reorder.output)

    reorder2.output.toTensor[Float] should be (t1)
  }
}
