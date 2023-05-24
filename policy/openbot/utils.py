# Created by Matthias Mueller - Intel Intelligent Systems Lab - 2020

import csv
import matplotlib.pyplot as plt
import tensorflow as tf
import numpy as np
import os
from IPython.display import Javascript
from nbconvert import HTMLExporter
import codecs
import nbformat

# Select non-interactive backend for compatability
plt.switch_backend("agg")


def save_notebook():
    Javascript("IPython.notebook.save_notebook()")


def output_HTML(read_file, output_file):
    exporter = HTMLExporter()
    # read_file is '.ipynb', output_file is '.html'
    output_notebook = nbformat.read(read_file, as_version=4)
    output, resources = exporter.from_notebook_node(output_notebook)
    codecs.open(output_file, "w", encoding="utf-8").write(output)


def prepare_for_training(
    ds, batch_sz, shuffle_buffer_sz=10000, prefetch_buffer_sz=1000, cache=True
):
    # This is a small dataset, only load it once, and keep it in memory.
    # use `.cache(filename)` to cache preprocessing work for datasets that don't
    # fit in memory.
    if cache:
        if isinstance(cache, str):
            ds = ds.cache(cache)
        else:
            ds = ds.cache()

    ds = ds.shuffle(buffer_size=shuffle_buffer_sz)

    # Repeat forever
    ds = ds.repeat()

    ds = ds.batch(batch_sz)

    # `prefetch` lets the dataset fetch batches in the background while the model
    # is training.
    ds = ds.prefetch(buffer_size=prefetch_buffer_sz)

    return ds


def show_batch(dataset, policy="autopilot", model=None, fig_num=1):
    (image_batch, cmd_batch), label_batch = next(iter(dataset))
    NUM_SAMPLES = min(image_batch.numpy().shape[0], 15)

    if policy == "autopilot":
        command_input_name = "Cmd"
        size = (15, 10)
        if model is not None:
            pred_batch = model.predict(
                (
                    tf.slice(image_batch, [0, 0, 0, 0], [NUM_SAMPLES, -1, -1, -1]),
                    tf.slice(cmd_batch, [0], [NUM_SAMPLES]),
                )
            )
    elif policy == "point_goal_nav":
        command_input_name = "Goal"
        size = (15, 15)
        if model is not None:
            pred_batch = model.predict(
                (
                    tf.slice(image_batch, [0, 0, 0, 0], [NUM_SAMPLES, -1, -1, -1]),
                    tf.slice(cmd_batch, [0, 0], [NUM_SAMPLES, -1]),
                )
            )
    else:
        raise Exception("Unknown policy")

    plt.figure(num=fig_num, figsize=size)

    for n in range(NUM_SAMPLES):
        ax = plt.subplot(5, 3, n + 1)
        plt.imshow(image_batch[n])
        if model is None:
            plt.title(
                "%s: %s, Label: [%.2f %.2f]"
                % (
                    command_input_name,
                    cmd_batch.numpy()[n],
                    float(label_batch[n][0]),
                    float(label_batch[n][1]),
                )
            )
        else:
            plt.title(
                "%s: %s, Label: [%.2f %.2f], Pred: [%.2f %.2f]"
                % (
                    command_input_name,
                    cmd_batch.numpy()[n],
                    float(label_batch[n][0]),
                    float(label_batch[n][1]),
                    float(pred_batch[n][0]),
                    float(pred_batch[n][1]),
                )
            )
        plt.axis("off")


def savefig(path):
    plt.savefig(path, bbox_inches="tight")
    plt.clf()


def generate_tflite(path, filename):
    converter = tf.lite.TFLiteConverter.from_saved_model(os.path.join(path, filename))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    return tflite_model


def save_tflite(tflite_model, path, filename):
    open(os.path.join(path, filename + ".tflite"), "wb").write(tflite_model)


def load_tflite(*parts):
    interpreter = tf.lite.Interpreter(model_path=os.path.join(*parts))
    return interpreter


def load_model(model_path, loss_fn, metric_list, custom_objects):
    model: tf.keras.Model = tf.keras.models.load_model(
        model_path, custom_objects=custom_objects, compile=False
    )
    model.compile(loss=loss_fn, metrics=metric_list)
    return model


def compare_tf_tflite(
    model, tflite_model, img=None, cmd=None, policy="autopilot", debug=False
):
    # Load TFLite model and allocate tensors.
    interpreter = tf.lite.Interpreter(model_content=tflite_model)
    interpreter.allocate_tensors()

    # Get input and output tensors.
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    if debug:
        print("input_details:", input_details)
        print("output_details:", output_details)

    if policy == "autopilot":
        command_input_name = "cmd_input"
    elif policy == "point_goal_nav":
        command_input_name = "goal_input"
    else:
        raise Exception("Unknown policy")

    # Test the TensorFlow Lite model on input data. If no data provided, generate random data.
    input_data = {}
    for input_detail in input_details:
        if "img_input" in input_detail["name"]:
            if img is None:
                input_data["img_input"] = np.array(
                    np.random.random_sample(input_detail["shape"]), dtype=np.float32
                )
            else:
                print(img)
                input_data["img_input"] = img
            interpreter.set_tensor(input_detail["index"], input_data["img_input"])
        elif command_input_name in input_detail["name"]:
            if cmd is None:
                input_data[command_input_name] = np.array(
                    np.random.random_sample(input_detail["shape"]), dtype=np.float32
                )
            else:
                print(cmd)
                input_data[input_detail["name"]] = cmd[0]
            interpreter.set_tensor(
                input_detail["index"], input_data[command_input_name]
            )
        else:
            ValueError("Unknown input")

    interpreter.invoke()

    # The function `get_tensor()` returns a copy of the tensor data.
    # Use `tensor()` in order to get a pointer to the tensor.
    tflite_results = interpreter.get_tensor(output_details[0]["index"])
    tf_results = model.predict(
        (
            tf.constant(input_data["img_input"]),
            tf.constant(input_data[command_input_name]),
        )
    )
    print("tflite:", tflite_results)
    print("tf:", tf_results)

    # Compare the result.
    for tf_result, tflite_result in zip(tf_results, tflite_results):
        print(
            "Almost equal (10% tolerance):",
            np.allclose(tf_result, tflite_result, rtol=0.1),
        )
        # np.testing.assert_almost_equal(tf_result, tflite_result, decimal=2)


def list_dirs(path):
    return [d for d in os.listdir(path) if os.path.isdir(os.path.join(path, d))]


def load_img(file_path, is_crop=False):
    img = tf.io.read_file(file_path)
    img = tf.image.decode_image(img, channels=3, dtype=tf.float32)
    if is_crop:
        img = tf.image.crop_to_bounding_box(
            img, tf.shape(img)[0] - 90, tf.shape(img)[1] - 160, 90, 160
        )
    return img


def read_csv_dict(csv_path):
    logs = []
    with open(csv_path, newline="") as csvfile:
        reader = csv.DictReader(csvfile)
        for row in reader:
            logs.append(row)
    return logs
